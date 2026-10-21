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

    private static final int MAX_OPEN_FILES = 512;

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
                         long nodeFingerprint, long relationshipFingerprint, Fixups fixups, long relationshipsToOutside) {
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
    private final DryRunProgress progress;

    private final Plans plans = new Plans();

    /**
     * @param filePrefix put in front of the names of the files, so that several exports can share one directory
     * @param workers    how many threads read and write at the same time
     */
    public CsvExporter(Source source, IdSet ids, List<Rule> rules, String filePrefix, Path directory, int batchSize,
                       int workers, DryRunProgress progress) {
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

        progress.phase("Export, pass 2 of 2: writing the CSV files");
        List<Scanner.Part<Pass2>> written = scanner.run(Pass2::new);
        List<Fixups> fixupParts = new ArrayList<>();
        List<String> nodeFiles = new ArrayList<>();
        List<String> relationshipFiles = new ArrayList<>();
        long nodes = 0;
        long relationships = 0;
        long nodeHash = 0;
        long relationshipHash = 0;
        long outside = 0;
        for (Scanner.Part<Pass2> part : written) {
            Pass2 local = part.result();
            fixupParts.add(local.fixups());
            nodeFiles.addAll(local.nodeFiles.names());
            relationshipFiles.addAll(local.relationshipFiles.names());
            nodes += local.nodeCount;
            relationships += local.relationshipCount;
            nodeHash += local.nodeHash;
            relationshipHash += local.relationshipHash;
            outside += part.outside();
        }
        return new Export(nodeFiles, relationshipFiles, nodes, relationships, nodeHash, relationshipHash, Fixups.of(fixupParts), outside);
    }

    // ---- pass 1 ----

    /** The property types seen so far, per label set (nodes) and per type (relationships). */
    private static final class Plans {

        private final Map<String, GroupPlan> nodePlans = new TreeMap<>();
        private final Map<String, GroupPlan> relationshipPlans = new TreeMap<>();

        void observeNode(long id, List<String> labels, Map<String, Value> props) {
            GroupPlan plan = nodePlans.computeIfAbsent(labelKey(labels), k -> new GroupPlan());
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

        Pass2(int worker) {
            nodeFiles = new Writers(filePrefix + "nodes-w" + worker, true);
            relationshipFiles = new Writers(filePrefix + "relationships-w" + worker, false);
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
        public void accept(long id, List<String> labels, Map<String, Value> props) {
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
        public void accept(long start, long end, String type, long relationshipId, Map<String, Value> props) {
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

    /** The CSV files of one kind of entity, opened when the first row for them arrives. */
    private final class Writers {

        private final String prefix;
        private final boolean nodes;
        private final Map<String, BufferedWriter> writers = new HashMap<>();
        private final Map<String, List<String>> columns = new HashMap<>();
        private final List<String> names = new ArrayList<>();

        Writers(String prefix, boolean nodes) {
            this.prefix = prefix;
            this.nodes = nodes;
        }

        List<String> names() {
            return names;
        }

        void write(String groupKey, GroupPlan plan, Map<String, ValueCodec.Encoded> cells, Object... leading) {
            // Properties with one type are shared columns; a property with several types picks the file by its type.
            StringBuilder variant = new StringBuilder(groupKey).append('\u0001');
            List<String> conflicts = plan.conflictKeys();
            for (String key : conflicts) {
                ValueCodec.Encoded encoded = cells.get(key);
                variant.append(key).append('=').append(encoded == null ? "-" : encoded.type()).append('\u0001');
            }
            String fileKey = variant.toString();
            BufferedWriter writer = writers.get(fileKey);
            List<String> header = columns.get(fileKey);
            try {
                if (writer == null) {
                    if (writers.size() >= MAX_OPEN_FILES) {
                        throw new BuildException("The selection needs more than " + MAX_OPEN_FILES + " different CSV layouts "
                                + "(label sets or property type combinations). Split it into smaller selections.");
                    }
                    String name = prefix + "-" + names.size() + ".csv";
                    names.add(name);
                    writer = Files.newBufferedWriter(directory.resolve(name), StandardCharsets.UTF_8);
                    writers.put(fileKey, writer);
                    header = new ArrayList<>(plan.plainKeys());
                    for (String key : conflicts) {
                        if (cells.containsKey(key)) {
                            header.add(key);
                        }
                    }
                    columns.put(fileKey, header);
                    writer.write(nodes ? ":ID,:LABEL" : ":START_ID,:END_ID,:TYPE");
                    for (String key : header) {
                        ValueCodec.Encoded encoded = cells.get(key);
                        String type = conflicts.contains(key) ? encoded.type() : plan.plainType(key);
                        writer.write(',');
                        writer.write(escape(key + ":" + type));
                    }
                    writer.write('\n');
                }
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
                        String expected = conflicts.contains(key) ? encoded.type() : plan.plainType(key);
                        if (!expected.equals(encoded.type()) || !plan.knows(key, encoded.type())) {
                            throw new BuildException("The source changed while it was being exported: property '" + key
                                    + "' now has type " + encoded.type() + " where " + expected + " was planned. Run the split again.");
                        }
                        writer.write(escape(encoded.cell()));
                    }
                }
                if (present != cells.size()) {
                    List<String> known = header;
                    String unknown = cells.keySet().stream().filter(key -> !known.contains(key)).findFirst().orElse("?");
                    throw new BuildException("The source changed while it was being exported: a new property '" + unknown
                            + "' appeared. Run the split again.");
                }
                writer.write('\n');
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        void closeQuietly() {
            for (BufferedWriter writer : writers.values()) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                    // the export is already failing or finished
                }
            }
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
