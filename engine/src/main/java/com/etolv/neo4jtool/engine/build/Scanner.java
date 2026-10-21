package com.etolv.neo4jtool.engine.build;

import com.etolv.neo4jtool.engine.dryrun.DryRunProgress;
import it.unimi.dsi.fastutil.longs.LongIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reads a set of nodes (and the relationships of some {@link CsvExporter.Rule}s) on several threads. The sorted ids are
 * cut into one contiguous share per worker, so every worker walks the store in order. A worker owns what it collects: no
 * locks while reading.
 */
final class Scanner {

    /** What one worker does with the rows it reads, and the result it hands back. */
    interface Sink<T> extends CsvExporter.NodeVisitor, CsvExporter.RelationshipVisitor {

        /** Called after the worker has read all its rows. */
        T finish();

        /** Called when the worker stops early; the default does nothing. */
        default void abort() {
        }
    }

    interface SinkFactory<T> {
        Sink<T> create(int worker);
    }

    /** The result of one worker: what its sink returned and how many relationships its rules left out. */
    record Part<T>(T result, long outside) {
    }

    private final CsvExporter.Source source;
    private final IdSet ids;
    private final List<CsvExporter.Rule> rules;
    private final int batchSize;
    private final int workers;
    private final DryRunProgress progress;

    Scanner(CsvExporter.Source source, IdSet ids, List<CsvExporter.Rule> rules, int batchSize, int workers, DryRunProgress progress) {
        this.source = source;
        this.ids = ids;
        this.rules = rules;
        this.batchSize = batchSize;
        this.workers = (int) Math.max(1, Math.min(workers, ids.size()));
        this.progress = progress;
    }

    <T> List<Part<T>> run(SinkFactory<T> factory) {
        long size = ids.size();
        AtomicLong done = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable, "scan-worker");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<Part<T>>> futures = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                int worker = w;
                long from = size * w / workers;
                long to = size * (w + 1) / workers;
                futures.add(pool.submit(() -> one(factory.create(worker), worker, from, to, done, size)));
            }
            List<Part<T>> results = new ArrayList<>();
            for (Future<Part<T>> future : futures) {
                results.add(future.get());
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BuildException("The read was interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new BuildException("The read failed: " + cause, cause);
        } finally {
            pool.shutdownNow();
        }
    }

    private <T> Part<T> one(Sink<T> sink, int worker, long from, long to, AtomicLong done, long total) {
        long outside = 0;
        try (CsvExporter.Reader reader = source.open()) {
            LongIterator iterator = ids.iteratorFrom(from);
            long position = from;
            while (position < to) {
                int n = (int) Math.min(batchSize, to - position);
                long[] batch = new long[n];
                for (int i = 0; i < n; i++) {
                    batch[i] = iterator.nextLong();
                }
                outside += reader.read(batch, rules, ids, sink, sink);
                position += n;
                long all = done.addAndGet(n);
                if (worker == 0) {
                    progress.progress(all, total);
                }
            }
            return new Part<>(sink.finish(), outside);
        } catch (RuntimeException | Error e) {
            sink.abort();
            throw e;
        }
    }
}
