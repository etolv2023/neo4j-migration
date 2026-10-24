package com.etolv.neo4jtool.engine.build;

import com.etolv.neo4jtool.engine.Cypher;
import com.etolv.neo4jtool.engine.conn.Neo4jClient;
import com.etolv.neo4jtool.engine.dryrun.DryRunProgress;
import com.etolv.neo4jtool.engine.selection.SelectionConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

/**
 * The dry run's answer to "what happens to the selected nodes whose id the target already uses?": the new ids they would
 * get and which properties of the company's nodes hold the old ones. It reads only.
 */
public final class RenumberPlanner {

    /** Most rows of the id table the report keeps; the merge writes all of them to a file. */
    public static final int MAPPING_LIMIT = 20_000;

    /** One renumbered node: the id it has, the one it would get, and what the target holds at the old id. */
    public record Entry(long oldId, long newId, List<String> labels, Object org, List<String> targetLabels, Object targetOrg) {
    }

    /**
     * @param nodes             how many nodes would get a new id
     * @param mapping           the first {@link #MAPPING_LIMIT} of them
     * @param references        null when the properties were not searched
     */
    public record Plan(String allocation, long nodes, long firstNewId, long lastNewId, List<Entry> mapping, boolean mappingTruncated,
                       boolean lists, boolean includeNewerNodes, References references) {
    }

    /**
     * Where an old id is held as a property value.
     *
     * @param moved    the nodes that are restored (including the renumbered ones)
     * @param existing company nodes already on the target that the source also had
     * @param newer    company nodes only the target has (made after the backup); they are changed only if asked
     * @param outside  nodes of the target that are not the company's; they are never changed, this only tells where an old id is
     *                 held there too. Null when it was not searched
     */
    public record References(ReferenceScan.Result moved, ReferenceScan.Result existing, ReferenceScan.Result newer,
                             ReferenceScan.Result outside) {

        public References(ReferenceScan.Result moved, ReferenceScan.Result existing, ReferenceScan.Result newer) {
            this(moved, existing, newer, null);
        }

        public long nodesToChange(boolean includeNewer) {
            return moved.nodesWithReferences() + existing.nodesWithReferences() + (includeNewer ? newer.nodesWithReferences() : 0);
        }

        public long valuesToChange(boolean includeNewer) {
            return moved.values() + existing.values() + (includeNewer ? newer.values() : 0);
        }
    }

    /** Receives every row of the id table, in batches, while the plan is made. */
    public interface MappingSink {
        void accept(List<Entry> batch);
    }

    private RenumberPlanner() {
    }

    public static Plan plan(Neo4jClient source, FastReader sourceFast, Neo4jClient target, FastReader targetFast,
                            SelectionConfig selection, IdSet clash, IdSet missing, RenumberOptions options, int batchSize, int workers,
                            int examples, DryRunProgress progress) {
        return plan(source, sourceFast, target, targetFast, selection, clash, missing, options, batchSize, workers, examples, progress,
                null);
    }

    /**
     * @param clash   selected ids the target uses for another node
     * @param missing selected ids the target does not have
     * @param sink    gets every row of the id table (the plan itself keeps the first {@link #MAPPING_LIMIT}); may be null
     */
    public static Plan plan(Neo4jClient source, FastReader sourceFast, Neo4jClient target, FastReader targetFast,
                            SelectionConfig selection, IdSet clash, IdSet missing, RenumberOptions options, int batchSize, int workers,
                            int examples, DryRunProgress progress, MappingSink sink) {
        progress.phase("Choosing new ids");
        IdSet sourceAll = sourceFast != null ? sourceFast.allIds(workers, progress) : Ids.all(source);
        IdRemap remap;
        if (options.allocation() == RenumberOptions.Allocation.FILL_GAPS) {
            IdSet targetAll = targetFast != null ? targetFast.allIds(workers, progress) : Ids.all(target);
            remap = IdRemap.inGaps(clash, IdSet.union(targetAll, missing));
        } else {
            long targetMax = targetFast != null ? targetFast.allIds(workers, progress).max() : highestId(target);
            remap = IdRemap.above(clash, Math.max(targetMax, sourceAll.max()) + 1);
        }
        List<Entry> mapping = mapping(source, target, remap, selection.orgProperty(), batchSize, sink);

        String org = selection.orgProperty();
        progress.phase("Looking for the old ids in the properties of the moved nodes");
        ReferenceScan.Result inMoved = scan("MOVED", source, sourceFast, IdSet.union(missing, clash), remap, org, options, batchSize,
                workers, examples, progress);
        ReferenceScan.Result inExisting;
        ReferenceScan.Result inNewer;
        IdSet companyIds = IdSet.empty();
        if (selection.orgIds().isEmpty()) {
            inExisting = new ReferenceScan.Result("EXISTING", 0, 0, 0, List.of());
            inNewer = new ReferenceScan.Result("NEWER", 0, 0, 0, List.of());
        } else {
            progress.phase("Looking for the old ids in the company's nodes already on the target");
            SelectionConfig company = new SelectionConfig(org, selection.orgIds(), null, null, null, null, false);
            companyIds = targetFast != null ? targetFast.select(company, workers, progress) : Ids.selected(target, company);
            inExisting = scan("EXISTING", target, targetFast, companyIds.intersect(sourceAll), remap, org, options, batchSize, workers,
                    examples, progress);
            inNewer = scan("NEWER", target, targetFast, companyIds.minus(sourceAll), remap, org, options, batchSize, workers,
                    examples, progress);
        }
        ReferenceScan.Result inOutside = null;
        if (options.scanOutside()) {
            progress.phase("Looking for the old ids outside the company, in the rest of the target");
            IdSet targetAll = targetFast != null ? targetFast.allIds(workers, progress) : Ids.all(target);
            inOutside = scan("OUTSIDE", target, targetFast, targetAll.minus(companyIds), remap, org, options, batchSize, workers,
                    examples, progress);
        }
        References references = new References(inMoved, inExisting, inNewer, inOutside);
        long[] news = remap.newIds();
        return new Plan(options.allocation().name(), remap.size(), news[0], remap.highestNewId(), mapping,
                remap.size() > MAPPING_LIMIT, options.lists(), options.includeNewerNodes(), references);
    }

    private static ReferenceScan.Result scan(String scope, Neo4jClient client, FastReader fast, IdSet ids, IdRemap remap, String org,
                                             RenumberOptions options, int batchSize, int workers, int examples, DryRunProgress progress) {
        return fast != null
                ? fast.scanReferences(scope, ids, remap, org, options.lists(), batchSize, workers, examples, progress)
                : ReferenceScan.run(scope, CsvExporter.bolt(client), ids, remap, org, options.lists(), batchSize, workers, examples, progress);
    }

    private static long highestId(Neo4jClient client) {
        try (Session session = client.readSession()) {
            Result result = session.run("MATCH (n) RETURN id(n) AS id ORDER BY id DESC LIMIT 1");
            return result.hasNext() ? result.next().get("id").asLong() : -1;
        }
    }

    private static List<Entry> mapping(Neo4jClient source, Neo4jClient target, IdRemap remap, String org, int batch, MappingSink sink) {
        long[] olds = remap.oldIds();
        int wanted = sink == null ? Math.min(olds.length, MAPPING_LIMIT) : olds.length;
        List<Entry> entries = new ArrayList<>(Math.min(wanted, MAPPING_LIMIT));
        for (int from = 0; from < wanted; from += batch) {
            long[] slice = java.util.Arrays.copyOfRange(olds, from, Math.min(wanted, from + batch));
            List<Entry> part = new ArrayList<>(slice.length);
            Long2ObjectOpenHashMap<Object[]> inSource = identities(source, slice, org);
            Long2ObjectOpenHashMap<Object[]> inTarget = identities(target, slice, org);
            for (long id : slice) {
                Object[] s = inSource.getOrDefault(id, new Object[] {List.<String>of(), null});
                Object[] t = inTarget.getOrDefault(id, new Object[] {List.<String>of(), null});
                @SuppressWarnings("unchecked")
                Entry entry = new Entry(id, remap.map(id), (List<String>) s[0], s[1], (List<String>) t[0], t[1]);
                part.add(entry);
                if (entries.size() < MAPPING_LIMIT) {
                    entries.add(entry);
                }
            }
            if (sink != null) {
                sink.accept(part);
            }
        }
        return entries;
    }

    private static Long2ObjectOpenHashMap<Object[]> identities(Neo4jClient client, long[] ids, String org) {
        Long2ObjectOpenHashMap<Object[]> found = new Long2ObjectOpenHashMap<>();
        try (Session session = client.readSession()) {
            Result result = session.run("UNWIND $ids AS i MATCH (n) WHERE id(n) = i RETURN id(n) AS id, labels(n) AS labels, n."
                    + Cypher.ident(org) + " AS org", Map.of("ids", Values.value(ids)));
            while (result.hasNext()) {
                Record r = result.next();
                List<String> labels = new ArrayList<>(r.get("labels").asList(Value::asString));
                Collections.sort(labels);
                Value value = r.get("org");
                found.put(r.get("id").asLong(), new Object[] {labels, value.isNull() ? null : value.asObject()});
            }
        }
        return found;
    }
}
