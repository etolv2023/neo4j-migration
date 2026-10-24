package com.etolv.neo4jtool.engine.build;

import com.etolv.neo4jtool.engine.dryrun.DryRunProgress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.Value;

/**
 * Finds, without changing anything, the properties of some nodes that hold the old id of a node that is about to get a new
 * one, and what they would become. Used by the dry run, with the same {@link ReferenceRewrite} that the merge applies.
 */
public final class ReferenceScan {

    /** One node's property that would change: what it holds now and what it would hold. */
    public record Example(long nodeId, String before, String after) {
    }

    /**
     * Properties of one kind: the nodes of one label set, one property, one kind of value.
     *
     * @param scope  MOVED (nodes that are restored), EXISTING (company nodes already on the target) or NEWER (company
     *               nodes the source did not have)
     * @param labels the labels of the nodes, sorted and joined by ':'
     * @param type   INTEGER, STRING, INTEGER LIST or STRING LIST
     * @param count  how many properties (one per node)
     */
    public record Group(String scope, String labels, String property, String type, long count, List<Example> examples) {
    }

    /**
     * @param nodesScanned        how many nodes were looked at
     * @param nodesWithReferences nodes with at least one such property
     * @param values              properties found, in all groups
     */
    public record Result(String scope, long nodesScanned, long nodesWithReferences, long values, List<Group> groups) {
    }

    private ReferenceScan() {
    }

    /**
     * @param source      where the nodes are read
     * @param ids         the nodes to look at
     * @param remap       old ids and the ids they would get
     * @param orgProperty never looked at: it holds the organisation
     * @param lists       also look inside list properties
     * @param examples    how many examples each group keeps
     */
    public static Result run(String scope, CsvExporter.Source source, IdSet ids, IdRemap remap, String orgProperty, boolean lists,
                             int batchSize, int workers, int examples, DryRunProgress progress) {
        if (ids.size() == 0 || remap.isEmpty()) {
            return new Result(scope, ids.size(), 0, 0, List.of());
        }
        ReferenceRewrite rewrite = ReferenceRewrite.forMovedNodes(remap, orgProperty, lists, java.util.Set.of());
        Scanner scanner = new Scanner(source, ids, List.of(), batchSize, workers, progress);
        List<Scanner.Part<Tally>> parts = scanner.run(worker -> new Tally(rewrite, examples));
        Tally total = new Tally(rewrite, examples);
        parts.forEach(part -> total.add(part.result()));
        return total.result(scope, ids.size());
    }

    /** What one worker found. */
    private static final class Tally implements Scanner.Sink<Tally> {

        private record Key(String labels, String property, String type) {
        }

        private static final class Counts {
            long count;
            final List<Example> examples = new ArrayList<>();
        }

        private final ReferenceRewrite rewrite;
        private final int examples;
        private final Map<Key, Counts> groups = new LinkedHashMap<>();
        private long nodesWithReferences;
        private long values;

        Tally(ReferenceRewrite rewrite, int examples) {
            this.rewrite = rewrite;
            this.examples = examples;
        }

        @Override
        public void accept(long id, List<String> labels, Map<String, Value> props) {
            boolean[] found = new boolean[1];
            List<String> sorted = labels.size() > 1 ? labels.stream().sorted().toList() : labels;
            rewrite.apply(sorted, props, (property, before, after) -> {
                found[0] = true;
                values++;
                Counts counts = groups.computeIfAbsent(new Key(String.join(":", sorted), property, kind(before)), k -> new Counts());
                counts.count++;
                if (counts.examples.size() < examples) {
                    counts.examples.add(new Example(id, text(before), text(after)));
                }
            });
            if (found[0]) {
                nodesWithReferences++;
            }
        }

        @Override
        public void accept(long start, long end, String type, long id, Map<String, Value> props) {
            // relationships are not read
        }

        @Override
        public Tally finish() {
            return this;
        }

        void add(Tally other) {
            nodesWithReferences += other.nodesWithReferences;
            values += other.values;
            other.groups.forEach((key, counts) -> {
                Counts mine = groups.computeIfAbsent(key, k -> new Counts());
                mine.count += counts.count;
                for (Example example : counts.examples) {
                    if (mine.examples.size() < examples) {
                        mine.examples.add(example);
                    }
                }
            });
        }

        Result result(String scope, long scanned) {
            List<Group> list = new ArrayList<>();
            groups.forEach((key, counts) -> {
                counts.examples.sort(Comparator.comparingLong(Example::nodeId));
                list.add(new Group(scope, key.labels(), key.property(), key.type(), counts.count, counts.examples));
            });
            list.sort(Comparator.comparingLong(Group::count).reversed().thenComparing(Group::labels).thenComparing(Group::property));
            return new Result(scope, scanned, nodesWithReferences, values, list);
        }

        private static String kind(Value value) {
            String type = value.type().name();
            if (type.startsWith("LIST")) {
                for (Value item : value.values()) {
                    return item.type().name() + " LIST";
                }
                return "LIST";
            }
            return type;
        }

        private static String text(Value value) {
            String text = value.toString();
            return text.length() > 200 ? text.substring(0, 200) + "..." : text;
        }
    }
}
