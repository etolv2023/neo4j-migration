package com.etolv.neo4jtool.engine.fast;

import com.etolv.neo4jtool.engine.build.BuildException;
import com.etolv.neo4jtool.engine.build.CsvExporter;
import com.etolv.neo4jtool.engine.build.FastReader;
import com.etolv.neo4jtool.engine.build.Fixups;
import com.etolv.neo4jtool.engine.build.IdSet;
import com.etolv.neo4jtool.engine.build.Verifier;
import com.etolv.neo4jtool.engine.dryrun.DryRunProgress;
import com.etolv.neo4jtool.engine.selection.SelectionConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Starts the fast reader (module {@code reader}) as a child Java process and talks to it in JSON lines. The process holds
 * the store open, read only; this class is the parent's handle on it.
 */
public final class ReaderProcess implements FastReader {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> JVM_OPENS = List.of("--add-opens=java.base/java.nio=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED", "--enable-native-access=ALL-UNNAMED");

    /**
     * How to start the reader.
     *
     * @param classpath      the class path of the reader process: the {@code reader/lib/*} directory, or a test's class path
     * @param heap           heap of the reader process, for example "2g"
     * @param pageCacheBytes page cache of the reader's database
     * @param scratch        where the reader keeps its scratch directory and where id lists are exchanged
     */
    public record Settings(String classpath, String heap, long pageCacheBytes, Path scratch, Duration startTimeout) {

        public Settings {
            heap = heap == null || heap.isBlank() ? "2g" : heap;
            startTimeout = startTimeout == null ? Duration.ofMinutes(10) : startTimeout;
        }
    }

    /** A factory that starts reader processes with the given settings. */
    public static FastReader.Factory factory(Settings settings) {
        return (dataDirectory, database, bolt, log) -> start(settings, dataDirectory, database, bolt, log);
    }

    private final Process process;
    private final BufferedWriter toChild;
    private final Path exchange;
    private volatile String boltUri;
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Deque<String> errorTail = new ArrayDeque<>();
    private volatile DryRunProgress progress = DryRunProgress.NONE;
    private volatile Consumer<String> log;
    private long nextId = 1;
    private volatile boolean closed;

    private ReaderProcess(Process process, Path exchange, Consumer<String> log) {
        this.process = process;
        this.toChild = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.exchange = exchange;
        this.log = log;
    }

    public static ReaderProcess start(Settings settings, Path dataDirectory, String database, boolean bolt, Consumer<String> log) {
        try {
            Files.createDirectories(settings.scratch());
            Path exchange = Files.createTempDirectory(settings.scratch(), "exchange-");
            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-Xmx" + settings.heap());
            command.add("-XX:+UseParallelGC");
            command.addAll(JVM_OPENS);
            command.add("-cp");
            command.add(settings.classpath());
            command.add("com.etolv.neo4jtool.reader.ReaderServer");
            command.add(dataDirectory.toAbsolutePath().toString());
            command.add(database);
            command.add(settings.scratch().toAbsolutePath().toString());
            command.add(Long.toString(settings.pageCacheBytes()));
            command.add(Boolean.toString(bolt));
            log.accept("Starting the fast reader on " + dataDirectory);
            Process process = new ProcessBuilder(command).start();
            CompletableFuture<JsonNode> ready = new CompletableFuture<>();
            ReaderProcess reader = new ReaderProcess(process, exchange, log);
            reader.listen(ready);
            JsonNode event;
            try {
                event = ready.get(settings.startTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                process.destroyForcibly();
                throw new BuildException("The fast reader did not open the store in " + settings.startTimeout().toMinutes() + " minutes");
            }
            if (event.path("event").asText().equals("failed")) {
                process.destroyForcibly();
                throw new BuildException(event.path("message").asText());
            }
            reader.boltUri = event.path("bolt").isNull() || event.path("bolt").isMissingNode() ? null : event.path("bolt").asText();
            return reader;
        } catch (IOException e) {
            throw new BuildException("Could not start the fast reader: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BuildException("Interrupted while starting the fast reader", e);
        } catch (ExecutionException e) {
            throw new BuildException("The fast reader stopped before it was ready: " + e.getCause().getMessage(), e.getCause());
        }
    }

    // ---- reading what the process says ----

    private void listen(CompletableFuture<JsonNode> ready) {
        Thread out = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    JsonNode event = JSON.readTree(line);
                    String kind = event.path("event").asText();
                    switch (kind) {
                        case "ready", "failed" -> ready.complete(event);
                        case "phase" -> progress.phase(event.path("name").asText());
                        case "progress" -> progress.progress(event.path("done").asLong(), event.path("total").asLong());
                        case "result", "error" -> {
                            CompletableFuture<JsonNode> waiting = pending.remove(event.path("id").asLong());
                            if (waiting != null) {
                                waiting.complete(event);
                            }
                        }
                        default -> log.accept(line);
                    }
                }
            } catch (IOException e) {
                // the process ended; handled below
            } finally {
                ready.completeExceptionally(new BuildException(exited()));
                pending.values().forEach(f -> f.completeExceptionally(new BuildException(exited())));
            }
        }, "reader-output");
        out.setDaemon(true);
        out.start();
        Thread err = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    synchronized (errorTail) {
                        errorTail.addLast(line);
                        if (errorTail.size() > 20) {
                            errorTail.removeFirst();
                        }
                    }
                    log.accept(line);
                }
            } catch (IOException ignored) {
                // the process ended
            }
        }, "reader-errors");
        err.setDaemon(true);
        err.start();
    }

    private String exited() {
        if (closed) {
            return "The fast reader was closed";
        }
        synchronized (errorTail) {
            return "The fast reader stopped unexpectedly" + (errorTail.isEmpty() ? "" : ":\n" + String.join("\n", errorTail));
        }
    }

    // ---- commands ----

    @Override
    public String boltUri() {
        return boltUri;
    }

    private synchronized JsonNode call(String op, ObjectNode arguments, DryRunProgress progress) {
        long id = nextId++;
        arguments.put("id", id);
        arguments.put("op", op);
        CompletableFuture<JsonNode> waiting = new CompletableFuture<>();
        pending.put(id, waiting);
        this.progress = progress;
        try {
            toChild.write(arguments.toString());
            toChild.write('\n');
            toChild.flush();
            JsonNode reply = waiting.get();
            if (reply.path("event").asText().equals("error")) {
                throw new BuildException(reply.path("message").asText());
            }
            return reply.path("result");
        } catch (IOException e) {
            throw new BuildException(exited(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BuildException("Interrupted while waiting for the fast reader", e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof BuildException build ? build : new BuildException(exited(), e.getCause());
        } finally {
            pending.remove(id);
            this.progress = DryRunProgress.NONE;
        }
    }

    @Override
    public IdSet select(SelectionConfig selection, int workers, DryRunProgress progress) {
        ObjectNode args = JSON.createObjectNode();
        args.put("workers", workers);
        Path out = exchange.resolve("selected-" + System.nanoTime() + ".ids");
        args.put("out", out.toString());
        ObjectNode s = args.putObject("selection");
        s.put("orgProperty", selection.orgProperty());
        ArrayNode orgIds = s.putArray("orgIds");
        for (Object value : selection.orgIds()) {
            if (value instanceof Number n && !(value instanceof Double) && !(value instanceof Float)) {
                orgIds.add(n.longValue());
            } else if (value instanceof Number n) {
                orgIds.add(n.doubleValue());
            } else if (value instanceof Boolean b) {
                orgIds.add(b);
            } else {
                orgIds.add(value.toString());
            }
        }
        ArrayNode labels = s.putArray("labels");
        selection.labels().forEach(labels::add);
        ArrayNode ids = s.putArray("ids");
        selection.ids().forEach(ids::add);
        ArrayNode ranges = s.putArray("idRanges");
        selection.idRanges().forEach(r -> ranges.addArray().add(r.get(0)).add(r.get(1)));
        ArrayNode excluded = s.putArray("excludeIds");
        selection.excludeIds().forEach(excluded::add);
        s.put("includeNeighbours", selection.includeNeighbours());
        call("select", args, progress);
        return readAndDelete(out);
    }

    @Override
    public IdSet allIds(int workers, DryRunProgress progress) {
        ObjectNode args = JSON.createObjectNode();
        args.put("workers", workers);
        Path out = exchange.resolve("all-" + System.nanoTime() + ".ids");
        args.put("out", out.toString());
        call("all", args, progress);
        return readAndDelete(out);
    }

    private static IdSet readAndDelete(Path file) {
        try {
            return IdSet.read(file);
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // removed with the exchange directory
            }
        }
    }

    @Override
    public CsvExporter.Export export(IdSet ids, List<CsvExporter.Rule> rules, String prefix, Path directory, int batchSize,
                                     int workers, int layoutColumns, DryRunProgress progress) {
        Map<IdSet, Path> written = new IdentityHashMap<>();
        ObjectNode args = JSON.createObjectNode();
        args.put("ids", fileOf(written, ids));
        ArrayNode ruleList = args.putArray("rules");
        for (CsvExporter.Rule rule : rules) {
            ObjectNode r = ruleList.addObject();
            r.put("outgoing", rule.outgoing());
            r.put("other", fileOf(written, rule.other()));
        }
        args.put("prefix", prefix);
        args.put("dir", directory.toAbsolutePath().toString());
        args.put("batch", batchSize);
        args.put("workers", workers);
        args.put("layoutColumns", layoutColumns);
        try {
            JsonNode result = call("export", args, progress);
            List<String> nodeFiles = new ArrayList<>();
            result.path("nodeFiles").forEach(f -> nodeFiles.add(f.asText()));
            List<String> relationshipFiles = new ArrayList<>();
            result.path("relationshipFiles").forEach(f -> relationshipFiles.add(f.asText()));
            List<Path> fixupFiles = new ArrayList<>();
            result.path("fixupFiles").forEach(f -> fixupFiles.add(Path.of(f.asText())));
            return new CsvExporter.Export(nodeFiles, relationshipFiles, result.path("nodes").asLong(),
                    result.path("relationships").asLong(), result.path("nodeFingerprint").asLong(),
                    result.path("relationshipFingerprint").asLong(), new Fixups(result.path("fixupCount").asLong(), fixupFiles),
                    result.path("outside").asLong());
        } finally {
            for (Path file : written.values()) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // removed with the exchange directory
                }
            }
        }
    }

    private String fileOf(Map<IdSet, Path> written, IdSet set) {
        return written.computeIfAbsent(set, s -> {
            Path file = exchange.resolve("export-" + System.nanoTime() + ".ids");
            s.write(file);
            return file;
        }).toString();
    }

    @Override
    public void verify(Verifier.Expected expected, int batchSize, int workers, DryRunProgress progress) {
        Path file = exchange.resolve("verify-" + System.nanoTime() + ".ids");
        expected.ids().write(file);
        try {
            ObjectNode args = JSON.createObjectNode();
            args.put("ids", file.toString());
            args.put("nodes", expected.nodes());
            args.put("relationships", expected.relationships());
            args.put("nodeFingerprint", expected.nodeFingerprint());
            args.put("relationshipFingerprint", expected.relationshipFingerprint());
            args.put("batch", batchSize);
            args.put("workers", workers);
            call("verify", args, progress);
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // removed with the exchange directory
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            toChild.write("{\"op\":\"quit\"}\n");
            toChild.flush();
            toChild.close();
        } catch (IOException ignored) {
            // already gone
        }
        try {
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                process.waitFor(30, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        try (var files = Files.walk(exchange)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // scratch
                }
            });
        } catch (IOException ignored) {
            // scratch
        }
    }
}
