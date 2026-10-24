package com.etolv.neo4jtool.engine.build;

import com.etolv.neo4jtool.engine.conn.Neo4jClient;
import com.etolv.neo4jtool.engine.dryrun.DryRunProgress;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.neo4j.driver.Value;

/**
 * Reads nodes, and the relationships chosen by its {@link Rule}s, from a database and writes them as CSV files for
 * {@code neo4j-admin database import full --id-type=actual}: the node id is the id column, so ids are kept.
 *
 * <p>It reads twice. The first pass only learns which property types occur, because a CSV column has one type and
 * the header must be written before the rows. The second pass writes. If the source changed in between so that a
 * value no longer fits the plan, the export stops and says so; it never writes something it did not plan.
 *
 * <p>Both passes run on several workers. Each worker takes one contiguous share of the sorted ids, so it reads the
 * store in order, and writes files of its own (no locks while writing). The totals, fingerprints and fixups are added up
 * afterwards; since the fingerprint is a sum, the result does not depend on how many workers there were.
 */
public final class CsvExporter {

    /**
     * How many CSV files one worker keeps open at once. A database can have thousands of different layouts (label
     * sets and property type combinations); the least recently used file is closed when the limit is reached and opened
     * again for appending when a row for it comes back, so the number of layouts is not limited by open files.
     */
    private static final int MAX_OPEN_FILES = 128;

    /**
     * Which relationships of the exported nodes are written: those that leave an exported node ({@link #out}) or
     * enter one ({@link #in}), and whose other end is in {@code other}. Relationships whose other end is not in
     * {@code other} are left out and counted, so the caller can report them.
     */
    public record Rule(boolean outgoing, IdSet other) {

        public static final int ACCEPT = 0;
        public static final int SKIP = 1;
        public static final int OUTSIDE = 2;

        public static Rule out(IdSet other) {
            return new Rule(true, other);
        }

        public static Rule in(IdSet other) {
            return new Rule(false, other);
        }

        /**
         * What to do with a relationship found at an exported node: {@link #ACCEPT} it, {@link #SKIP} it (an incoming
         * relationship from another exported node: the outgoing rule has written it already), or count it as
         * {@link #OUTSIDE} (its other end is not allowed).
         */
        public int classify(long start, long end, IdSet exported) {
            if (!outgoing && exported.contains(start)) {
                return SKIP;
            }
            return other.contains(outgoing ? end : start) ? ACCEPT : OUTSIDE;
        }
    }

    /** What the export produced, with the numbers the finished database is checked against. */
    public record Export(List<String> nodeFiles, List<String> relationshipFiles, long nodes, long relationships,
                         long nodeFingerprint, long relationshipFingerprint, Fixups fixups, long relationshipsToOutside,
                         long renumberedNodes, long rewrittenNodes, long rewrittenValues) {

        public Export(List<String> nodeFiles, List<String> relationshipFiles, long nodes, long relationships,
                      long nodeFingerprint, long relationshipFingerprint, Fixups fixups, long relationshipsToOutside) {
            this(nodeFiles, relationshipFiles, nodes, relationships, nodeFingerprint, relationshipFingerprint, fixups,
                    relationshipsToOutside, 0, 0, 0);
        }
    }

    public interface NodeVisitor {
        void accept(long id, List<String> labels, Map<String, Value> props);
    }

    public interface RelationshipVisitor {
        void accept(long start, long end, String type, long relationshipId, Map<String, Value> props);
    }

    /** Where the data is read from. */
    public interface Source {

        /** A reader for one worker thread; the worker closes it. */
        Reader open();

        /** How many nodes the whole database has. */
        long nodeCount();

        /** How many relationships the whole database has. */
        long relationshipCount();
    }

    /** Reads batches of nodes and their relationships. Used by one thread. */
    public interface Reader extends AutoCloseable {

        /**
         * Reads the nodes {@code ids} and, for each {@code rule}, their relationships. Every id must still exist.
         *
         * @return how many relationships were left out because of their rule ({@link Rule#OUTSIDE})
         */
        long read(long[] ids, List<Rule> rules, IdSet exported, NodeVisitor nodes, RelationshipVisitor relationships);

        @Override
        void close();
    }

    private static final String NODES = "UNWIND $ids AS i MATCH (n) WHERE id(n) = i "
            + "RETURN id(n) AS id, labels(n) AS labels, properties(n) AS props";
    private static final String RELATIONSHIPS_OUT = "UNWIND $ids AS i MATCH (a) WHERE id(a) = i MATCH (a)-[r]->(b) "
            + "RETURN id(a) AS a, id(b) AS b, type(r) AS type, id(r) AS rid, properties(r) AS props";
    private static final String RELATIONSHIPS_IN = "UNWIND $ids AS i MATCH (b) WHERE id(b) = i MATCH (a)-[r]->(b) "
            + "RETURN id(a) AS a, id(b) AS b, type(r) AS type, id(r) AS rid, properties(r) AS props";

    /** Reads over Bolt, one session per batch. */
    public static Source bolt(Neo4jClient client) {
        return new Source() {
            @Override
            public long nodeCount() {
                return count(client, "MATCH (n) RETURN count(n) AS c");
            }

            @Override
            public long relationshipCount() {
                return count(client, "MATCH ()-[r]->() RETURN count(r) AS c");
            }

            @Override
            public Reader open() {
                return boltReader(client);
            }
        };
    }

    private static long count(Neo4jClient client, String query) {
        try (org.neo4j.driver.Session session = client.readSession()) {
            return session.run(query).single().get("c").asLong();
        }
    }

    private static Reader boltReader(Neo4jClient client) {
        return new Reader() {
            @Override
            public long read(long[] batch, List<Rule> rules, IdSet exported, NodeVisitor nodes, RelationshipVisitor relationships) {
                long outside = 0;
                java.util.Map<String, Object> params = java.util.Map.of("ids", org.neo4j.driver.Values.value(batch));
                try (org.neo4j.driver.Session session = client.readSession()) {
                    int seen = 0;
                    org.neo4j.driver.Result result = session.run(NODES, params);
                    while (result.hasNext()) {
                        org.neo4j.driver.Record r = result.next();
                        seen++;
                        List<String> labels = new ArrayList<>(r.get("labels").asList(Value::asString));
                        nodes.accept(r.get("id").asLong(), labels, r.get("props").asMap(v -> v));
                    }
                    if (seen != batch.length) {
                        throw new BuildException((batch.length - seen) + " of the selected nodes are gone from the source. "
                                + "The source changed while it was being read; run the split again.");
                    }
                    for (Rule rule : rules) {
                        result = session.run(rule.outgoing() ? RELATIONSHIPS_OUT : RELATIONSHIPS_IN, params);
                        while (result.hasNext()) {
                            org.neo4j.driver.Record r = result.next();
                            long start = r.get("a").asLong();
                            long end = r.get("b").asLong();
                            int what = rule.classify(start, end, exported);
                            if (what == Rule.SKIP) {
                                continue;
                            }
                            if (what == Rule.OUTSIDE) {
                                outside++;
                                continue;
                            }
                            relationships.accept(start, end, r.get("type").asString(), r.get("rid").asLong(),
                                    r.get("props").asMap(v -> v));
                        }
                    }
                }
                return outside;
            }

            @Override
            public void close() {
                // sessions are opened and closed per batch
            }
        };
    }

    private final Source source;
    private final IdSet ids;
    private final List<Rule> rules;
    private final String filePrefix;
    private final Path directory;
    private final int batchSize;
    private final int workers;
    private final int layoutColumns;
    private final Renumbering renumbering;
    private final DryRunProgress progress;

    private final Plans plans = new Plans();
    private final Layouts nodeLayouts = new Layouts();
    private final Layouts relationshipLayouts = new Layouts();

    /**
     * @param filePrefix put in front of the names of the files, so that several exports can share one directory
     * @param workers    how many threads read and write at the same time
     */
    public CsvExporter(Source source, IdSet ids, List<Rule> rules, String filePrefix, Path directory, int batchSize,
                       int workers, DryRunProgress progress) {
        this(source, ids, rules, filePrefix, directory, batchSize, workers, 0, progress);
    }

    /**
     * @param layoutColumns the most columns a layout shared by several label sets may have; 0 writes a layout per distinct
     *                      set of columns, as it always did (see {@link LayoutMerger})
     */
    public CsvExporter(Source source, IdSet ids, List<Rule> rules, String filePrefix, Path directory, int batchSize,
                       int workers, int layoutColumns, DryRunProgress progress) {
        this(source, ids, rules, filePrefix, directory, batchSize, workers, layoutColumns, Renumbering.none(), progress);
    }

    /**
     * @param renumbering writes the nodes of {@code ids} that it names under their new id, with the ends of their
     *                    relationships to match, and replaces the property values that are an old id (see {@link Renumbering})
     */
    public CsvExporter(Source source, IdSet ids, List<Rule> rules, String filePrefix, Path directory, int batchSize,
                       int workers, int layoutColumns, Renumbering renumbering, DryRunProgress progress) {
        this.renumbering = renumbering;
        this.layoutColumns = layoutColumns;
        this.source = source;
        this.ids = ids;
        this.rules = rules;
        this.filePrefix = filePrefix;
        this.directory = directory;
        this.batchSize = batchSize;
        this.workers = workers;
        this.progress = progress;
    }

    public Export export() {
        Scanner scanner = new Scanner(source, ids, rules, batchSize, workers, progress);

        progress.phase("Export, pass 1 of 2: finding out which property types occur");
        List<Scanner.Part<Plans>> observed = scanner.run(worker -> new Scanner.Sink<Plans>() {
            private final Plans local = new Plans();

            @Override
            public void accept(long id, List<String> labels, Map<String, Value> props) {
                local.observeNode(id, labels, props);
            }

            @Override
            public void accept(long start, long end, String type, long relationshipId, Map<String, Value> props) {
                local.observeRelationship(type, relationshipId, props);
            }

            @Override
            public Plans finish() {
                return local;
            }
        });
        for (Scanner.Part<Plans> part : observed) {
            plans.merge(part.result());
        }
        plans.freeze();
        if (layoutColumns > 0) {
            collectVariants();
        }
        LayoutMerger.Result merged = LayoutMerger.merge(plans.nodePlans.values(), layoutColumns);
        progress.phase(layoutColumns > 0
                ? "Export: sharing node layouts (at most " + layoutColumns + " columns): " + merged.describe()
                : "Export: node layouts are not shared (the setting is off): " + plans.nodePlans.size() + " groups of nodes");

        progress.phase("Export, pass 2 of 2: writing the CSV files");
        List<Scanner.Part<Pass2>> written = scanner.run(Pass2::new);
        List<Fixups> fixupParts = new ArrayList<>();
        long nodes = 0;
        long relationships = 0;
        long nodeHash = 0;
        long relationshipHash = 0;
        long outside = 0;
        long renumbered = 0;
        long rewrittenNodes = 0;
        long rewrittenValues = 0;
        List<Writers> nodeWriters = new ArrayList<>();
        List<Writers> relationshipWriters = new ArrayList<>();
        for (Scanner.Part<Pass2> part : written) {
            Pass2 local = part.result();
            fixupParts.add(local.fixups());
            nodeWriters.add(local.nodeFiles);
            relationshipWriters.add(local.relationshipFiles);
            nodes += local.nodeCount;
            relationships += local.relationshipCount;
            nodeHash += local.nodeHash;
            relationshipHash += local.relationshipHash;
            outside += part.outside();
            renumbered += local.renumberedNodes;
            rewrittenNodes += local.rewrittenNodes;
            rewrittenValues += local.rewrittenValues;
        }
        List<String> nodeFiles = nodeLayouts.finish("nodes", filePrefix, directory, nodeWriters);
        List<String> relationshipFiles = relationshipLayouts.finish("relationships", filePrefix, directory, relationshipWriters);
        progress.phase("Export: " + nodeLayouts.count() + " node layouts and " + relationshipLayouts.count()
                + " relationship layouts written by " + written.size() + " workers");
        return new Export(nodeFiles, relationshipFiles, nodes, relationships, nodeHash, relationshipHash, Fixups.of(fixupParts), outside,
                renumbered, rewrittenNodes, rewrittenValues);
    }

    // ---- pass 1b: the combinations of conflicting types ----

    /** How many nodes of one group have one combination of types of its conflicting properties. */
    private static final class Counted {
        private final Map<String, String> types;
        private long rows;

        Counted(Map<String, String> types) {
            this.types = types;
        }
    }

    /** What one worker found in pass 1b, per group of nodes. */
    private static final class VariantCounts {
        private final Map<String, Map<String, Counted>> byGroup = new HashMap<>();
        private final java.util.Set<String> overflowed = new java.util.HashSet<>();
    }

    /**
     * A group whose property has several types is written to one layout per combination of types. To let those
     * combinations share layouts too, they must be known before the first row is written: this reads the nodes once more,
     * only the groups with conflicts, and counts the combinations. Without it each combination keeps a layout of its own.
     */
    private void collectVariants() {
        long conflicting = plans.nodePlans.values().stream().filter(plan -> !plan.conflictKeys().isEmpty()).count();
        if (conflicting == 0) {
            progress.phase("Export, pass 1b of 2: no group of nodes has conflicting property types, nothing to look at");
            return;
        }
        progress.phase("Export, pass 1b of 2: finding out which combinations of conflicting property types occur in "
                + conflicting + " groups of nodes");
        Scanner nodesOnly = new Scanner(source, ids, List.of(), batchSize, workers, progress);
        List<Scanner.Part<VariantCounts>> parts = nodesOnly.run(worker -> new Scanner.Sink<VariantCounts>() {
            private final VariantCounts local = new VariantCounts();

            @Override
            public void accept(long id, List<String> labels, Map<String, Value> props) {
                String group = labelKey(labels);
                GroupPlan plan = plans.nodePlans.get(group);
                if (plan == null) {
                    throw new BuildException("The source changed while it was being exported: nodes with labels ["
                            + group.replace('\u0000', ',') + "] appeared that were not there when it was read. Run the split again.");
                }
                List<String> conflicts = plan.conflictKeys();
                if (conflicts.isEmpty() || local.overflowed.contains(group)) {
                    return;
                }
                Map<String, String> types = new TreeMap<>();
                for (String key : conflicts) {
                    Value value = props.get(key);
                    if (value != null) {
                        ValueCodec.Encoded encoded = encodeNodeProperty(id, key, value);
                        if (encoded != null) {
                            types.put(key, encoded.type());
                        }
                    }
                }
                Map<String, Counted> variants = local.byGroup.computeIfAbsent(group, k -> new HashMap<>());
                Counted counted = variants.computeIfAbsent(GroupPlan.variantKey(types), k -> new Counted(types));
                counted.rows++;
                if (variants.size() > GroupPlan.MAX_VARIANTS) {
                    variants.clear();
                    local.overflowed.add(group);
                }
            }

            @Override
            public void accept(long start, long end, String type, long relationshipId, Map<String, Value> props) {
                throw new IllegalStateException("Relationships are not read in pass 1b");
            }

            @Override
            public VariantCounts finish() {
                return local;
            }
        });
        for (Scanner.Part<VariantCounts> part : parts) {
            VariantCounts counts = part.result();
            counts.byGroup.forEach((group, variants) -> variants.values()
                    .forEach(counted -> plans.nodePlans.get(group).addVariant(counted.types, counted.rows)));
            counts.overflowed.forEach(group -> plans.nodePlans.get(group).overflowVariants());
        }
        long overflowed = 0;
        long variants = 0;
        for (GroupPlan plan : plans.nodePlans.values()) {
            if (!plan.conflictKeys().isEmpty()) {
                plan.variantsDone();
                if (plan.variantsComplete()) {
                    variants += plan.variants().size();
                } else {
                    overflowed++;
                }
            }
        }
        progress.phase("Export, pass 1b of 2: found " + variants + " combinations of property types in " + (conflicting - overflowed)
                + " groups" + (overflowed > 0 ? "; " + overflowed + " groups have more than " + GroupPlan.MAX_VARIANTS
                + " combinations and keep a layout per combination" : ""));
    }

    // ---- pass 1 ----

    /** The property types seen so far, per label set (nodes) and per type (relationships). */
    private static final class Plans {

        private final Map<String, GroupPlan> nodePlans = new TreeMap<>();
        private final Map<String, GroupPlan> relationshipPlans = new TreeMap<>();

        void observeNode(long id, List<String> labels, Map<String, Value> props) {
            GroupPlan plan = nodePlans.computeIfAbsent(labelKey(labels), k -> new GroupPlan());
            plan.observeRow();
            for (Map.Entry<String, Value> property : props.entrySet()) {
                ValueCodec.Encoded encoded = encodeNodeProperty(id, property.getKey(), property.getValue());
                if (encoded != null) {
                    plan.observe(property.getKey(), encoded.type());
                }
            }
        }

        void observeRelationship(String type, long relationshipId, Map<String, Value> props) {
            GroupPlan plan = relationshipPlans.computeIfAbsent(type, k -> new GroupPlan());
            for (Map.Entry<String, Value> property : props.entrySet()) {
                ValueCodec.Encoded encoded = encodeRelationshipProperty(type, relationshipId, property.getKey(), property.getValue());
                plan.observe(property.getKey(), encoded.type());
            }
        }

        void merge(Plans other) {
            other.nodePlans.forEach((key, plan) -> nodePlans.computeIfAbsent(key, k -> new GroupPlan()).merge(plan));
            other.relationshipPlans.forEach((key, plan) -> relationshipPlans.computeIfAbsent(key, k -> new GroupPlan()).merge(plan));
        }

        /** Makes the plans safe to read from several threads. */
        void freeze() {
            nodePlans.values().forEach(GroupPlan::freeze);
            relationshipPlans.values().forEach(GroupPlan::freeze);
        }
    }

    /** @return the cell, or null when this property must be written in the second pass instead */
    private static ValueCodec.Encoded encodeNodeProperty(long id, String key, Value value) {
        if (!ValueCodec.isSafeKey(key)) {
            return null;
        }
        try {
            return ValueCodec.encode(value);
        } catch (UnsupportedOperationException e) {
            throw new BuildException("Node " + id + ", property '" + key + "': " + e.getMessage(), e);
        }
    }

    private static ValueCodec.Encoded encodeRelationshipProperty(String type, long relationshipId, String key, Value value) {
        ValueCodec.Encoded encoded = null;
        String reason = null;
        if (!ValueCodec.isSafeKey(key)) {
            reason = "its name cannot be a CSV column";
        } else {
            try {
                encoded = ValueCodec.encode(value);
            } catch (UnsupportedOperationException e) {
                reason = e.getMessage();
            }
            if (encoded == null && reason == null) {
                reason = "the CSV import cannot carry an empty list or a date-time with a named time zone";
            }
        }
        if (encoded == null) {
            throw new BuildException("Relationship " + relationshipId + " of type '" + type + "', property '" + key
                    + "': " + reason + ". Copying such properties is only supported for nodes. "
                    + "Leave the relationship type out of the selection's reach or fix the data in the source first.");
        }
        return encoded;
    }

    // ---- pass 2 ----

    /** What one worker writes in the second pass. */
    private final class Pass2 implements Scanner.Sink<Pass2> {

        final Writers nodeFiles;
        final Writers relationshipFiles;
        final String fixupName;
        java.io.BufferedWriter fixupWriter;
        long fixupCount;
        long nodeCount;
        long relationshipCount;
        long nodeHash;
        long relationshipHash;
        long renumberedNodes;
        long rewrittenNodes;
        long rewrittenValues;
        private final ReferenceRewrite.Listener counting = (property, before, after) -> rewrittenValues++;

        Pass2(int worker) {
            nodeFiles = new Writers("nodes", worker, true, nodeLayouts);
            relationshipFiles = new Writers("relationships", worker, false, relationshipLayouts);
            fixupName = filePrefix + "fixups-w" + worker + ".jsonl";
        }

        private void fixup(long id, String key, Value value) {
            try {
                if (fixupWriter == null) {
                    fixupWriter = Files.newBufferedWriter(directory.resolve(fixupName), StandardCharsets.UTF_8);
                }
                fixupWriter.write(ValueJson.write(id, key, value));
                fixupWriter.write('\n');
                fixupCount++;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        Fixups fixups() {
            try {
                if (fixupWriter != null) {
                    fixupWriter.close();
                    fixupWriter = null;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return fixupCount == 0 ? Fixups.none() : new Fixups(fixupCount, List.of(directory.resolve(fixupName)));
        }

        @Override
        public void accept(long sourceId, List<String> labels, Map<String, Value> original) {
            long id = renumbering.ids().map(sourceId);
            if (id != sourceId) {
                renumberedNodes++;
            }
            Map<String, Value> props = original;
            ReferenceRewrite rewrite = renumbering.rewrite();
            if (rewrite != null && rewrite.appliesTo(sourceId, original)) {
                props = rewrite.apply(labels, original, counting);
                if (props != original) {
                    rewrittenNodes++;
                }
            }
            GroupPlan plan = plans.nodePlans.get(labelKey(labels));
            Map<String, ValueCodec.Encoded> cells = new LinkedHashMap<>();
            for (Map.Entry<String, Value> property : props.entrySet()) {
                ValueCodec.Encoded encoded = encodeNodeProperty(id, property.getKey(), property.getValue());
                if (encoded == null) {
                    fixup(id, property.getKey(), property.getValue());
                } else {
                    cells.put(property.getKey(), encoded);
                }
            }
            List<String> sortedLabels = new ArrayList<>(labels);
            java.util.Collections.sort(sortedLabels);
            String labelCell = String.join(String.valueOf(ValueCodec.ARRAY_DELIMITER), sortedLabels);
            nodeFiles.write(labelKey(labels), plan, cells, id, labelCell);
            nodeCount++;
            nodeHash += Fingerprint.node(id, labels, props);
        }

        @Override
        public void accept(long sourceStart, long sourceEnd, String type, long relationshipId, Map<String, Value> props) {
            long start = renumbering.ids().map(sourceStart);
            long end = renumbering.ids().map(sourceEnd);
            GroupPlan plan = plans.relationshipPlans.get(type);
            Map<String, ValueCodec.Encoded> cells = new LinkedHashMap<>();
            for (Map.Entry<String, Value> property : props.entrySet()) {
                cells.put(property.getKey(), encodeRelationshipProperty(type, relationshipId, property.getKey(), property.getValue()));
            }
            relationshipFiles.write(type, plan, cells, start, end, type);
            relationshipCount++;
            relationshipHash += Fingerprint.relationship(start, end, type, props);
        }

        @Override
        public Pass2 finish() {
            closeQuietly();
            return this;
        }

        @Override
        public void abort() {
            closeQuietly();
        }

        void closeQuietly() {
            nodeFiles.closeQuietly();
            relationshipFiles.closeQuietly();
            if (fixupWriter != null) {
                try {
                    fixupWriter.close();
                } catch (IOException ignored) {
                    // the export is already failing or finished
                }
            }
        }
    }

    private static String labelKey(List<String> labels) {
        List<String> sorted = new ArrayList<>(labels);
        java.util.Collections.sort(sorted);
        return String.join("\u0000", sorted);
    }

    /**
     * The layouts of one kind of entity (nodes or relationships). Every distinct layout gets a number shared by all
     * workers, so that all the parts of a layout can be named together: {@code nodes-h3.csv} holds the header,
     * {@code nodes-p3-w0.csv}, {@code nodes-p3-w1.csv}, ... hold the rows written by each worker, without a header.
     */
    private static final class Layouts {

        private final java.util.concurrent.ConcurrentHashMap<String, Integer> numbers = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentHashMap<Integer, String> headers = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();

        int number(String key) {
            return numbers.computeIfAbsent(key, k -> next.getAndIncrement());
        }

        void header(int number, String header) {
            headers.putIfAbsent(number, header);
        }

        int count() {
            return next.get();
        }

        /** Writes the header files and lists, per layout, its header and then its parts. */
        List<String> finish(String kind, String prefix, Path directory, List<Writers> workers) {
            List<String> names = new ArrayList<>();
            for (int number = 0; number < next.get(); number++) {
                String header = prefix + kind + "-h" + number + ".csv";
                try {
                    Files.writeString(directory.resolve(header), headers.get(number), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                names.add(header);
                for (Writers worker : workers) {
                    if (worker.wrote(number)) {
                        names.add(worker.partName(prefix, number));
                    }
                }
            }
            return names;
        }
    }

    /** The CSV files of one worker for one kind of entity, opened when the first row for them arrives. */
    private final class Writers {

        private final String kind;
        private final int worker;
        private final boolean nodes;
        private final Layouts layouts;
        private final Map<String, Integer> numbers = new HashMap<>();
        private final Map<Integer, List<String>> columns = new HashMap<>();
        private final java.util.Set<Integer> created = new java.util.HashSet<>();
        /** Access order: the first entry is the file that was used longest ago. */
        private final java.util.LinkedHashMap<Integer, BufferedWriter> open = new java.util.LinkedHashMap<>(16, 0.75f, true);

        Writers(String kind, int worker, boolean nodes, Layouts layouts) {
            this.kind = kind;
            this.worker = worker;
            this.nodes = nodes;
            this.layouts = layouts;
        }

        boolean wrote(int number) {
            return created.contains(number);
        }

        String partName(String prefix, int number) {
            return prefix + kind + "-p" + number + "-w" + worker + ".csv";
        }

        void write(String groupKey, GroupPlan plan, Map<String, ValueCodec.Encoded> cells, Object... leading) {
            // A group that shares a layout writes to it. Otherwise properties with one type are shared columns of the
            // group's own layouts and a property with several types picks the file by its type.
            List<String> conflicts = plan.conflictKeys();
            GroupPlan.Shape shape = null;
            if (nodes) {
                Map<String, String> conflictTypes = new TreeMap<>();
                for (String key : conflicts) {
                    ValueCodec.Encoded encoded = cells.get(key);
                    if (encoded != null) {
                        conflictTypes.put(key, encoded.type());
                    }
                }
                shape = plan.sharedShape(conflictTypes);
            }
            String fileKey;
            if (shape != null) {
                fileKey = "shared\u0001" + shape.id();
            } else {
                StringBuilder variant = new StringBuilder(groupKey).append('\u0001');
                for (String key : conflicts) {
                    ValueCodec.Encoded encoded = cells.get(key);
                    variant.append(key).append('=').append(encoded == null ? "-" : encoded.type()).append('\u0001');
                }
                fileKey = variant.toString();
            }
            Integer known = numbers.get(fileKey);
            int number = known != null ? known : layoutOf(fileKey, plan, shape, conflicts, cells);
            try {
                BufferedWriter writer = open.get(number);
                if (writer == null) {
                    writer = openFile(number);
                }
                List<String> header = columns.get(number);
                for (int i = 0; i < leading.length; i++) {
                    if (i > 0) {
                        writer.write(',');
                    }
                    writer.write(escape(leading[i].toString(), false));
                }
                int present = 0;
                for (String key : header) {
                    writer.write(',');
                    ValueCodec.Encoded encoded = cells.get(key);
                    if (encoded != null) {
                        present++;
                        if (!plan.knows(key, encoded.type())) {
                            throw new BuildException("The source changed while it was being exported: property '" + key
                                    + "' now has type " + encoded.type() + ", which it did not have when it was read. Run the split again.");
                        }
                        writer.write(escape(encoded.cell()));
                    }
                }
                if (present != cells.size()) {
                    List<String> known2 = header;
                    String unknown = cells.keySet().stream().filter(key -> !known2.contains(key)).findFirst().orElse("?");
                    throw new BuildException("The source changed while it was being exported: a new property '" + unknown
                            + "' appeared. Run the split again.");
                }
                writer.write('\n');
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /**
         * The layout a group of rows goes to. Layouts are told apart by their header, not by the labels: the labels are a
         * column of every row, so groups whose rows have the same columns share one layout. A database with thousands of
         * label sets (one per customer, say) usually has only a few hundred different headers.
         */
        private int layoutOf(String fileKey, GroupPlan plan, GroupPlan.Shape shape, List<String> conflicts,
                             Map<String, ValueCodec.Encoded> cells) {
            List<String> header;
            if (shape != null) {
                header = shape.keys();
            } else {
                header = new ArrayList<>(plan.plainKeys());
                for (String key : conflicts) {
                    if (cells.containsKey(key)) {
                        header.add(key);
                    }
                }
            }
            StringBuilder line = new StringBuilder(nodes ? ":ID,:LABEL" : ":START_ID,:END_ID,:TYPE");
            for (String key : header) {
                String type = shape != null ? shape.types().get(key)
                        : conflicts.contains(key) ? cells.get(key).type() : plan.plainType(key);
                line.append(',').append(escape(key + ":" + type));
            }
            String text = line.append('\n').toString();
            int number = layouts.number(text);
            layouts.header(number, text);
            columns.putIfAbsent(number, header);
            numbers.put(fileKey, number);
            return number;
        }

        /** Opens the part of a layout: new the first time, for appending when it was closed to keep the open files few. */
        private BufferedWriter openFile(int number) throws IOException {
            Path file = directory.resolve(partName(filePrefix, number));
            BufferedWriter writer;
            if (created.add(number)) {
                writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            } else {
                writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
            }
            open.put(number, writer);
            if (open.size() > MAX_OPEN_FILES) {
                java.util.Iterator<Map.Entry<Integer, BufferedWriter>> oldest = open.entrySet().iterator();
                Map.Entry<Integer, BufferedWriter> eldest = oldest.next();
                oldest.remove();
                eldest.getValue().close();
            }
            return writer;
        }

        void closeQuietly() {
            for (BufferedWriter writer : open.values()) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                    // the export is already failing or finished
                }
            }
            open.clear();
        }
    }

    private static String escape(String text) {
        return escape(text, true);
    }

    /**
     * CSV quoting. An empty text is written as "" because in the import an empty cell means "no value" while a quoted
     * empty cell is an empty string.
     */
    private static String escape(String text, boolean emptyIsQuoted) {
        if (text.isEmpty()) {
            return emptyIsQuoted ? "\"\"" : "";
        }
        boolean quote = text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0
                || text.charAt(0) == ' ' || text.charAt(text.length() - 1) == ' ';
        return quote ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }
}
