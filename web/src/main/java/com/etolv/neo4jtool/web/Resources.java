package com.etolv.neo4jtool.web;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * What this machine has and uses right now: memory, swap, the disk the work folder is on, the processor, and what the
 * processes started by the dashboard (neo4j-admin, the fast reader) hold. Read from the operating system; a value it cannot
 * tell is null and the page leaves it out. A reading is kept for a second so many open pages cost one.
 */
@Service
public class Resources {

    /**
     * Sizes are in bytes; {@code cpuPercent} is 0 to 100. {@code iowaitPercent} is the share of the processor's time spent waiting
     * for a disk, and the two rates are what the disks of the machine read and wrote per second, both over the time since the
     * reading before (null until there are two close readings).
     */
    public record Snapshot(Long ramTotal, Long ramUsed, Long swapTotal, Long swapUsed, Long diskTotal, Long diskFree,
                           Integer cpuPercent, Long jobRss, Integer jobProcesses, Integer iowaitPercent, Long diskReadPerSecond,
                           Long diskWritePerSecond) {
    }

    /** What /proc counted up to a moment: processor time (all, waiting for disks), and the sectors the disks read and wrote. */
    record Counters(long at, long cpuTotal, long cpuIowait, long sectorsRead, long sectorsWritten) {
    }

    /** Two readings further apart than this say nothing about now. */
    private static final long MAX_GAP_MILLIS = 15_000;
    private static final long SECTOR = 512;
    private Counters before;

    private final java.util.function.Supplier<String> workDir;
    private Snapshot cached;
    private long cachedAt;

    @org.springframework.beans.factory.annotation.Autowired
    public Resources(SettingsStore settings) {
        this(() -> settings.get().workDir());
    }

    /** For tests: the folder whose disk is measured. */
    Resources(java.util.function.Supplier<String> workDir) {
        this.workDir = workDir;
    }

    public synchronized Snapshot read() {
        long now = System.currentTimeMillis();
        if (cached == null || now - cachedAt > 900) {
            cached = sample();
            cachedAt = now;
        }
        return cached;
    }

    private Snapshot sample() {
        Long ramTotal = null;
        Long ramUsed = null;
        Long swapTotal = null;
        Long swapUsed = null;
        Map<String, Long> memory = meminfo();
        if (memory.containsKey("MemTotal") && memory.containsKey("MemAvailable")) {
            ramTotal = memory.get("MemTotal");
            ramUsed = Math.max(0, ramTotal - memory.get("MemAvailable"));
        } else if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            ramTotal = os.getTotalMemorySize();
            ramUsed = Math.max(0, ramTotal - os.getFreeMemorySize());
        }
        if (memory.containsKey("SwapTotal") && memory.containsKey("SwapFree")) {
            swapTotal = memory.get("SwapTotal");
            swapUsed = Math.max(0, swapTotal - memory.get("SwapFree"));
        }
        Long diskTotal = null;
        Long diskFree = null;
        try {
            Path folder = Path.of(workDir.get()).toAbsolutePath();
            while (folder != null && !Files.exists(folder)) {
                folder = folder.getParent();
            }
            if (folder != null) {
                var store = Files.getFileStore(folder);
                diskTotal = store.getTotalSpace();
                diskFree = store.getUsableSpace();
            }
        } catch (IOException | RuntimeException e) {
            // the page leaves the disk out
        }
        Integer cpu = null;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            double load = os.getCpuLoad();
            if (load >= 0) {
                cpu = (int) Math.round(load * 100);
            }
        }
        long rss = 0;
        int processes = 0;
        try {
            List<ProcessHandle> children = ProcessHandle.current().descendants().collect(Collectors.toList());
            for (ProcessHandle child : children) {
                long kb = residentKb(child.pid());
                if (kb > 0) {
                    rss += kb * 1024;
                    processes++;
                }
            }
        } catch (RuntimeException e) {
            // the page leaves the processes out
        }
        Counters now = counters();
        Integer iowait = null;
        Long readRate = null;
        Long writeRate = null;
        if (now != null && before != null && now.at() - before.at() >= 500 && now.at() - before.at() <= MAX_GAP_MILLIS) {
            long seconds1000 = now.at() - before.at();
            long cpuDelta = now.cpuTotal() - before.cpuTotal();
            if (cpuDelta > 0) {
                iowait = (int) Math.round((now.cpuIowait() - before.cpuIowait()) * 100.0 / cpuDelta);
            }
            readRate = Math.max(0, (now.sectorsRead() - before.sectorsRead()) * SECTOR * 1000 / seconds1000);
            writeRate = Math.max(0, (now.sectorsWritten() - before.sectorsWritten()) * SECTOR * 1000 / seconds1000);
        }
        if (now != null) {
            before = now;
        }
        return new Snapshot(ramTotal, ramUsed, swapTotal, swapUsed, diskTotal, diskFree, cpu, processes > 0 ? rss : null,
                processes > 0 ? processes : null, iowait, readRate, writeRate);
    }

    /** The counters of /proc now, or null where there are none (not Linux). */
    private static Counters counters() {
        try {
            Path stat = Path.of("/proc/stat");
            Path disks = Path.of("/proc/diskstats");
            if (!Files.isReadable(stat)) {
                return null;
            }
            long[] cpu = parseCpuStat(Files.readAllLines(stat));
            long[] sectors = Files.isReadable(disks) ? parseDiskstats(Files.readAllLines(disks)) : new long[] {0, 0};
            return new Counters(System.currentTimeMillis(), cpu[0], cpu[1], sectors[0], sectors[1]);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** From the first "cpu" line of /proc/stat: {all time, time waiting for disks}, in ticks. */
    static long[] parseCpuStat(List<String> lines) {
        for (String line : lines) {
            if (line.startsWith("cpu ")) {
                String[] parts = line.trim().split("\\s+");
                long total = 0;
                // user nice system idle iowait irq softirq steal; guest time is already in user
                for (int i = 1; i <= 8 && i < parts.length; i++) {
                    total += Long.parseLong(parts[i]);
                }
                return new long[] {total, parts.length > 5 ? Long.parseLong(parts[5]) : 0};
            }
        }
        throw new IllegalArgumentException("No cpu line");
    }

    private static final java.util.regex.Pattern WHOLE_DISK =
            java.util.regex.Pattern.compile("(sd[a-z]+|vd[a-z]+|xvd[a-z]+|hd[a-z]+|nvme\\d+n\\d+|mmcblk\\d+)");

    /** From /proc/diskstats: {sectors read, sectors written} by the whole disks (not their partitions, which would count twice). */
    static long[] parseDiskstats(List<String> lines) {
        long read = 0;
        long written = 0;
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length > 9 && WHOLE_DISK.matcher(parts[2]).matches()) {
                read += Long.parseLong(parts[5]);
                written += Long.parseLong(parts[9]);
            }
        }
        return new long[] {read, written};
    }

    /** /proc/meminfo in bytes, empty where there is none (not Linux). */
    static Map<String, Long> meminfo() {
        Path file = Path.of("/proc/meminfo");
        if (!Files.isReadable(file)) {
            return Map.of();
        }
        try {
            return parseMeminfo(Files.readAllLines(file));
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }

    static Map<String, Long> parseMeminfo(List<String> lines) {
        Map<String, Long> values = new java.util.HashMap<>();
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String[] parts = line.substring(colon + 1).trim().split("\\s+");
            try {
                values.put(line.substring(0, colon), Long.parseLong(parts[0]) * 1024);
            } catch (NumberFormatException e) {
                // not a number
            }
        }
        return values;
    }

    private static long residentKb(long pid) {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/" + pid + "/status"))) {
                if (line.startsWith("VmRSS:")) {
                    return Long.parseLong(line.substring(6).trim().split("\\s+")[0]);
                }
            }
        } catch (IOException | RuntimeException e) {
            // gone, or not readable
        }
        return 0;
    }
}
