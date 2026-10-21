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
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

/**
 * Reads nodes, and the relationships chosen by its {@link Rule}s, from a database and writes them as CSV files for
 * {@code neo4j-admin database import full --id-type=actual}: the node id is the id column, so ids are kept.
 *
 * <p>It reads twice. The first pass only learns which property types occur, because a CSV column has one type and
 * the header must be written before the rows. The second pass writes. If the source changed in between so that a
 * value no longer fits the plan, the export stops and says so; it never writes something it did not plan.
 */
final class CsvExporter {

    private static final int MAX_OPEN_FILES = 512;

    private static final String NODES = "UNWIND $ids AS i MATCH (n) WHERE id(n) = i "
            + "RETURN id(n) AS id, labels(n) AS labels, properties(n) AS props";
    private static final String RELATIONSHIPS_OUT = "UNWIND $ids AS i MATCH (a) WHERE id(a) = i MATCH (a)-[r]->(b) "
            + "RETURN id(a) AS a, id(b) AS b, type(r) AS type, id(r) AS rid, properties(r) AS props";
    private static final String RELATIONSHIPS_IN = "UNWIND $ids AS i MATCH (b) WHERE id(b) = i MATCH (a)-[r]->(b) "
            + "RETURN id(a) AS a, id(b) AS b, type(r) AS type, id(r) AS rid, properties(r) AS props";

    /**
     * Which relationships of the exported nodes are written: those that leave an exported node ({@link #OUT}) or
     * enter one ({@link #IN}), and whose other end is in {@code other}. Relationships whose other end is not in
     * {@code other} are left out and counted, so the caller can report them.
     */
    record Rule(boolean outgoing, IdSet other) {
        static Rule out(IdSet other) {
            return new Rule(true, other);
        }

        static Rule in(IdSet other) {
            return new Rule(false, other);
        }
    }

    /** What the export produced, with the numbers the finished database is checked against. */
    record Export(List<String> nodeFiles, List<String> relationshipFiles, long nodes, long relationships,
                  long nodeFingerprint, long relationshipFingerprint, Fixups fixups, long relationshipsToOutside) {
    }

    private final Neo4jClient source;
    private final IdSet ids;
    private final List<Rule> rules;
    private final String filePrefix;
    private final Path directory;
    private final int batchSize;
    private final DryRunProgress progress;

    private final Map<String, GroupPlan> nodePlans = new TreeMap<>();
    private final Map<String, GroupPlan> relationshipPlans = new TreeMap<>();

    /**
     * @param filePrefix put in front of the names of the files, so that several exports can share one directory
     */
    CsvExporter(Neo4jClient source, IdSet ids, List<Rule> rules, String filePrefix, Path directory, int batchSize,
                DryRunProgress progress) {
        this.source = source;
        this.ids = ids;
        this.rules = rules;
        this.filePrefix = filePrefix;
        this.directory = directory;
        this.batchSize = batchSize;
        this.progress = progress;
    }

    Export export() {
        progress.phase("Export, pass 1 of 2: finding out which property types occur");
        scan((id, labels, props) -> observeNode(id, labels, props), (a, b, type, rid, props) -> observeRelationship(type, rid, props));

        progress.phase("Export, pass 2 of 2: writing the CSV files");
        Fixups fixups = new Fixups();
        Writers nodeFiles = new Writers(filePrefix + "nodes", true);
        Writers relationshipFiles = new Writers(filePrefix + "relationships", false);
        long[] nodeCount = new long[1];
        long[] relationshipCount = new long[1];
        long[] nodeHash = new long[1];
        long[] relationshipHash = new long[1];
        long outside;
        try {
            outside = scan((id, labels, props) -> {
                writeNode(nodeFiles, fixups, id, labels, props);
                nodeCount[0]++;
                nodeHash[0] += Fingerprint.node(id, labels, props);
            }, (a, b, type, rid, props) -> {
                writeRelationship(relationshipFiles, a, b, type, rid, props);
                relationshipCount[0]++;
                relationshipHash[0] += Fingerprint.relationship(a, b, type, props);
            });
        } finally {
            nodeFiles.closeQuietly();
            relationshipFiles.closeQuietly();
        }
        return new Export(nodeFiles.names(), relationshipFiles.names(), nodeCount[0], relationshipCount[0], nodeHash[0],
                relationshipHash[0], fixups, outside);
    }

    // ---- reading ----

    private interface NodeVisitor {
        void accept(long id, List<String> labels, Map<String, Value> props);
    }

    private interface RelationshipVisitor {
        void accept(long start, long end, String type, long relationshipId, Map<String, Value> props);
    }

    /** @return how many relationships of the exported nodes were left out because of their {@link Rule} */
    private long scan(NodeVisitor nodes, RelationshipVisitor relationships) {
        long outside = 0;
        int total = ids.size();
        for (int from = 0; from < total; from += batchSize) {
            int to = Math.min(total, from + batchSize);
            long[] batch = ids.slice(from, to);
            Map<String, Object> params = Map.of("ids", Values.value(batch));
            try (Session session = source.readSession()) {
                int seen = 0;
                Result result = session.run(NODES, params);
                while (result.hasNext()) {
                    Record r = result.next();
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
                        Record r = result.next();
                        long start = r.get("a").asLong();
                        long end = r.get("b").asLong();
                        if (!rule.outgoing() && ids.contains(start)) {
                            continue;          // from an exported node: the outgoing rule has written it already
                        }
                        if (!rule.other().contains(rule.outgoing() ? end : start)) {
                            outside++;
                            continue;
                        }
                        relationships.accept(start, end, r.get("type").asString(), r.get("rid").asLong(),
                                r.get("props").asMap(v -> v));
                    }
                }
            }
            progress.progress(to, total);
        }
        return outside;
    }

    // ---- pass 1 ----

    private void observeNode(long id, List<String> labels, Map<String, Value> props) {
        GroupPlan plan = nodePlans.computeIfAbsent(labelKey(labels), k -> new GroupPlan());
        for (Map.Entry<String, Value> property : props.entrySet()) {
            ValueCodec.Encoded encoded = encodeNodeProperty(id, property.getKey(), property.getValue());
            if (encoded != null) {
                plan.observe(property.getKey(), encoded.type());
            }
        }
    }

    private void observeRelationship(String type, long relationshipId, Map<String, Value> props) {
        GroupPlan plan = relationshipPlans.computeIfAbsent(type, k -> new GroupPlan());
        for (Map.Entry<String, Value> property : props.entrySet()) {
            ValueCodec.Encoded encoded = encodeRelationshipProperty(type, relationshipId, property.getKey(), property.getValue());
            plan.observe(property.getKey(), encoded.type());
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

    private void writeNode(Writers files, Fixups fixups, long id, List<String> labels, Map<String, Value> props) {
        GroupPlan plan = nodePlans.get(labelKey(labels));
        Map<String, ValueCodec.Encoded> cells = new LinkedHashMap<>();
        for (Map.Entry<String, Value> property : props.entrySet()) {
            ValueCodec.Encoded encoded = encodeNodeProperty(id, property.getKey(), property.getValue());
            if (encoded == null) {
                fixups.add(id, property.getKey());
            } else {
                cells.put(property.getKey(), encoded);
            }
        }
        List<String> sortedLabels = new ArrayList<>(labels);
        java.util.Collections.sort(sortedLabels);
        String labelCell = String.join(String.valueOf(ValueCodec.ARRAY_DELIMITER), sortedLabels);
        files.write(labelKey(labels), plan, cells, List.of(Long.toString(id), labelCell));
    }

    private void writeRelationship(Writers files, long start, long end, String type, long relationshipId, Map<String, Value> props) {
        GroupPlan plan = relationshipPlans.get(type);
        Map<String, ValueCodec.Encoded> cells = new LinkedHashMap<>();
        for (Map.Entry<String, Value> property : props.entrySet()) {
            cells.put(property.getKey(), encodeRelationshipProperty(type, relationshipId, property.getKey(), property.getValue()));
        }
        files.write(type, plan, cells, List.of(Long.toString(start), Long.toString(end), type));
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

        void write(String groupKey, GroupPlan plan, Map<String, ValueCodec.Encoded> cells, List<String> leading) {
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
                for (int i = 0; i < leading.size(); i++) {
                    if (i > 0) {
                        writer.write(',');
                    }
                    writer.write(escape(leading.get(i), false));
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
