package com.etolv.neo4jtool.reader;

import com.etolv.neo4jtool.engine.build.BuildException;
import com.etolv.neo4jtool.engine.build.CsvExporter;
import com.etolv.neo4jtool.engine.build.IdRemap;
import com.etolv.neo4jtool.engine.build.IdSet;
import com.etolv.neo4jtool.engine.build.ReferenceRewrite;
import com.etolv.neo4jtool.engine.build.Renumbering;
import com.etolv.neo4jtool.engine.build.Verifier;
import com.etolv.neo4jtool.engine.dryrun.DryRunProgress;
import com.etolv.neo4jtool.engine.selection.SelectionConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The fast reader process. It opens one database from its store files (read only), serves Bolt on a free local port for
 * everything that asks Cypher questions, and answers a few bulk commands with the kernel API on many threads.
 *
 * <p>Talks to its parent in JSON lines. On stdout (nothing else is ever written there): {@code ready} with the Bolt address,
 * then for each command {@code progress}/{@code phase}/{@code log} events and finally {@code result} or {@code error}.
 * On stdin: one command per line, {@code {"id":1,"op":"select",...}}; {@code quit} (or closing stdin) ends the process.
 */
public final class ReaderServer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PrintStream out;
    private final KernelSource source;
    private final Store store;

    private ReaderServer(PrintStream out, Store store, KernelSource source) {
        this.out = out;
        this.store = store;
        this.source = source;
    }

    /**
     * Arguments: {@code <data directory> <database> <scratch directory> <page cache bytes> <bolt: true|false>}
     */
    public static void main(String[] args) throws Exception {
        PrintStream protocol = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(System.err);           // nothing but protocol lines may reach stdout
        if (args.length < 5) {
            System.err.println("usage: ReaderServer <data directory> <database> <scratch> <page cache bytes> <bolt>");
            System.exit(2);
        }
        Store store;
        try {
            store = new Store(Path.of(args[0]), args[1], Path.of(args[2]), Long.parseLong(args[3]), Boolean.parseBoolean(args[4]));
        } catch (RuntimeException e) {
            ObjectNode failed = JSON.createObjectNode();
            failed.put("event", "failed");
            failed.put("message", String.valueOf(e.getMessage()));
            protocol.println(failed);
            System.exit(1);
            return;
        }
        try (store; KernelSource source = new KernelSource(store)) {
            ReaderServer server = new ReaderServer(protocol, store, source);
            ObjectNode ready = JSON.createObjectNode();
            ready.put("event", "ready");
            ready.put("bolt", store.boltUri());
            server.emit(ready);
            server.loop(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
        }
        System.exit(0);
    }

    private void loop(BufferedReader in) throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode command = JSON.readTree(line);
            String op = command.path("op").asText();
            if (op.equals("quit")) {
                return;
            }
            long id = command.path("id").asLong();
            try {
                ObjectNode result = execute(op, command, id);
                ObjectNode reply = JSON.createObjectNode();
                reply.put("event", "result");
                reply.put("id", id);
                reply.set("result", result);
                emit(reply);
            } catch (Exception e) {
                ObjectNode reply = JSON.createObjectNode();
                reply.put("event", "error");
                reply.put("id", id);
                reply.put("message", e instanceof BuildException || e instanceof IllegalArgumentException
                        ? e.getMessage() : e.toString());
                emit(reply);
            }
        }
    }

    private ObjectNode execute(String op, JsonNode c, long id) {
        DryRunProgress progress = progress(id);
        int workers = Math.max(1, c.path("workers").asInt(1));
        switch (op) {
            case "select": {
                IdSet ids = new Selecting(source, workers, progress).select(selection(c.get("selection")));
                ids.write(Path.of(c.get("out").asText()));
                return idsResult(ids);
            }
            case "all": {
                IdSet ids = new Selecting(source, workers, progress).all();
                ids.write(Path.of(c.get("out").asText()));
                return idsResult(ids);
            }
            case "export": {
                Map<String, IdSet> loaded = new HashMap<>();
                IdSet ids = load(loaded, c.get("ids").asText());
                List<CsvExporter.Rule> rules = new ArrayList<>();
                for (JsonNode rule : c.get("rules")) {
                    IdSet other = load(loaded, rule.get("other").asText());
                    rules.add(rule.get("outgoing").asBoolean() ? CsvExporter.Rule.out(other) : CsvExporter.Rule.in(other));
                }
                CsvExporter.Export export = new CsvExporter(source, ids, rules, c.path("prefix").asText(""),
                        Path.of(c.get("dir").asText()), c.path("batch").asInt(10_000), workers, c.path("layoutColumns").asInt(0),
                        renumbering(c, loaded), progress).export();
                return exportResult(export);
            }
            case "scan": {
                Map<String, IdSet> loaded = new HashMap<>();
                com.etolv.neo4jtool.engine.build.ReferenceScan.Result found = com.etolv.neo4jtool.engine.build.ReferenceScan.run(
                        c.path("scope").asText(""), source, load(loaded, c.get("ids").asText()),
                        IdRemap.read(Path.of(c.get("remap").asText())), c.get("orgProperty").asText(), c.path("lists").asBoolean(false),
                        c.path("batch").asInt(10_000), workers, c.path("examples").asInt(20), progress);
                return (ObjectNode) JSON.valueToTree(found);
            }
            case "verify": {
                IdSet ids = IdSet.read(Path.of(c.get("ids").asText()));
                Verifier.Expected expected = new Verifier.Expected(ids, c.get("nodes").asLong(), c.get("relationships").asLong(),
                        c.get("nodeFingerprint").asLong(), c.get("relationshipFingerprint").asLong());
                Verifier.verify(source, expected, c.path("batch").asInt(10_000), workers, progress);
                return JSON.createObjectNode();
            }
            default:
                throw new IllegalArgumentException("Unknown command '" + op + "'");
        }
    }

    /** What the parent asked for about nodes whose id is taken on the target; nothing when it did not ask. */
    private static Renumbering renumbering(JsonNode c, Map<String, IdSet> loaded) {
        IdRemap ids = c.has("idRemap") ? IdRemap.read(Path.of(c.get("idRemap").asText())) : IdRemap.none();
        ReferenceRewrite rewrite = null;
        if (c.has("rewrite")) {
            JsonNode r = c.get("rewrite");
            IdRemap values = IdRemap.read(Path.of(r.get("remap").asText()));
            String orgProperty = r.get("orgProperty").asText();
            boolean lists = r.path("lists").asBoolean(false);
            java.util.Set<String> excluded = new java.util.HashSet<>();
            r.path("excluded").forEach(v -> excluded.add(v.asText()));
            if (r.has("orgIds")) {
                List<Object> orgIds = new ArrayList<>();
                for (JsonNode value : r.get("orgIds")) {
                    orgIds.add(value.isIntegralNumber() ? (Object) value.asLong() : value.isNumber() ? (Object) value.asDouble()
                            : value.isBoolean() ? (Object) value.asBoolean() : value.asText());
                }
                IdSet eligible = r.has("eligible") ? load(loaded, r.get("eligible").asText()) : null;
                rewrite = ReferenceRewrite.forOrganisations(values, orgProperty, orgIds, eligible, lists, excluded);
            } else {
                rewrite = ReferenceRewrite.forMovedNodes(values, orgProperty, lists, excluded);
            }
        }
        return new Renumbering(ids, rewrite);
    }

    private static IdSet load(Map<String, IdSet> loaded, String path) {
        return loaded.computeIfAbsent(path, p -> IdSet.read(Path.of(p)));
    }

    private static ObjectNode idsResult(IdSet ids) {
        ObjectNode result = JSON.createObjectNode();
        result.put("count", ids.size());
        result.put("max", ids.max());
        return result;
    }

    private static ObjectNode exportResult(CsvExporter.Export export) {
        ObjectNode result = JSON.createObjectNode();
        ArrayNode nodeFiles = result.putArray("nodeFiles");
        export.nodeFiles().forEach(nodeFiles::add);
        ArrayNode relationshipFiles = result.putArray("relationshipFiles");
        export.relationshipFiles().forEach(relationshipFiles::add);
        result.put("nodes", export.nodes());
        result.put("relationships", export.relationships());
        result.put("nodeFingerprint", export.nodeFingerprint());
        result.put("relationshipFingerprint", export.relationshipFingerprint());
        result.put("outside", export.relationshipsToOutside());
        result.put("renumberedNodes", export.renumberedNodes());
        result.put("rewrittenNodes", export.rewrittenNodes());
        result.put("rewrittenValues", export.rewrittenValues());
        result.put("fixupCount", export.fixups().size());
        ArrayNode fixupFiles = result.putArray("fixupFiles");
        export.fixups().files().forEach(file -> fixupFiles.add(file.toString()));
        return result;
    }

    private static SelectionConfig selection(JsonNode s) {
        List<Object> orgIds = new ArrayList<>();
        for (JsonNode value : s.path("orgIds")) {
            orgIds.add(value.isIntegralNumber() ? (Object) value.asLong() : value.isNumber() ? (Object) value.asDouble()
                    : value.isBoolean() ? (Object) value.asBoolean() : value.asText());
        }
        List<String> labels = new ArrayList<>();
        s.path("labels").forEach(v -> labels.add(v.asText()));
        List<Long> ids = new ArrayList<>();
        s.path("ids").forEach(v -> ids.add(v.asLong()));
        List<List<Long>> ranges = new ArrayList<>();
        s.path("idRanges").forEach(r -> ranges.add(List.of(r.get(0).asLong(), r.get(1).asLong())));
        List<Long> excluded = new ArrayList<>();
        s.path("excludeIds").forEach(v -> excluded.add(v.asLong()));
        return new SelectionConfig(s.path("orgProperty").asText(null), orgIds, labels, ids, ranges, excluded,
                s.path("includeNeighbours").asBoolean(false));
    }

    // ---- talking to the parent ----

    private synchronized void emit(ObjectNode event) {
        out.println(event);
    }

    private DryRunProgress progress(long id) {
        return new DryRunProgress() {
            private long last;

            @Override
            public void phase(String name) {
                ObjectNode event = JSON.createObjectNode();
                event.put("event", "phase");
                event.put("id", id);
                event.put("name", name);
                emit(event);
            }

            @Override
            public void progress(long done, long total) {
                long now = System.nanoTime();
                if (done < total && now - last < 250_000_000L) {
                    return;
                }
                last = now;
                ObjectNode event = JSON.createObjectNode();
                event.put("event", "progress");
                event.put("id", id);
                event.put("done", done);
                event.put("total", total);
                emit(event);
            }
        };
    }
}
