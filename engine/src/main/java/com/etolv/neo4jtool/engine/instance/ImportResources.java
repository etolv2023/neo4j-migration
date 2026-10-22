package com.etolv.neo4jtool.engine.instance;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * How much memory {@code neo4j-admin import} gets. Left alone it sizes itself from the machine's "free" memory, which
 * does not count the file cache, and a server that has just loaded a big dump looks almost empty (a few hundred MB on a
 * 16 GB machine): the import then runs with tiny buffers and takes hours. This sizes it from the machine instead.
 *
 * <p>A fixed part of the machine stays for the operating system and the other programs (the dashboard, its database);
 * the import gets a heap of about an eighth of the machine and the rest as off-heap memory, but never more than what
 * the system says is available.
 */
public final class ImportResources {

    private static final long MIB = 1024L * 1024;
    private static final long GIB = 1024L * MIB;

    /** @param heapMb JVM heap of the import; @param offHeapMb memory it may use outside the heap */
    public record Plan(long heapMb, long offHeapMb, long totalMb, long availableMb) {

        public String heap() {
            return heapMb + "m";
        }

        public String offHeap() {
            return offHeapMb + "m";
        }

        public String describe() {
            return "Import memory: heap " + heapMb + " MB and off-heap " + offHeapMb + " MB (this machine has " + totalMb
                    + " MB, " + availableMb + " MB available)";
        }
    }

    private ImportResources() {
    }

    /** The plan for this machine. */
    public static Plan detect() {
        long total = totalBytes();
        long available = availableBytes(total);
        return plan(total, available);
    }

    static Plan plan(long totalBytes, long availableBytes) {
        long reserve = Math.max(3 * GIB, totalBytes / 4);
        long heap = Math.min(8 * GIB, Math.max(GIB, totalBytes / 8));
        long offHeap = totalBytes - reserve - heap;
        // what the system can really give right now: other programs may be using a lot
        offHeap = Math.min(offHeap, availableBytes - heap - 512 * MIB);
        offHeap = Math.max(offHeap, 512 * MIB);
        return new Plan(heap / MIB, offHeap / MIB, totalBytes / MIB, availableBytes / MIB);
    }

    private static long totalBytes() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                return os.getTotalMemorySize();
            }
        } catch (RuntimeException ignored) {
            // fall through to a modest guess
        }
        return 8 * GIB;
    }

    /** MemAvailable of /proc/meminfo: free memory plus the file cache that can be given back. */
    private static long availableBytes(long total) {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemAvailable:")) {
                    String[] parts = line.trim().split("\\s+");
                    return Long.parseLong(parts[1]) * 1024;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // not Linux: trust the total
        }
        return total;
    }
}
