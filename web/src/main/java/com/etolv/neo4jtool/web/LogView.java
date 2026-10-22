package com.etolv.neo4jtool.web;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/** How a line of a job log looks: its colour class, whether it is worth reading when the log is long, and its time. */
public final class LogView {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Pattern IMPORTANT = Pattern.compile(
            "^\\(\\d+/\\d+\\)|COMPLETED|^IMPORT |started$|finished|^Plan ready|^Backup: |^Export: | failed", Pattern.CASE_INSENSITIVE);

    private LogView() {
    }

    /** ERROR, WARN, STEP (the start of a step of the job) or INFO. */
    public static String cls(JobLog.Line line) {
        String level = line.level();
        if ("ERROR".equals(level) || "STEP".equals(level)) {
            return level;
        }
        String message = line.message();
        if (message.startsWith("WARNING") || message.startsWith("WARN ") || message.contains(" WARN ")) {
            return "WARN";
        }
        if (message.startsWith("Error ") || message.startsWith("Exception") || message.contains("FAILED")) {
            return "ERROR";
        }
        return "INFO";
    }

    /** Lines that tell where the job is, for the "Important only" view. */
    public static boolean important(JobLog.Line line) {
        String cls = cls(line);
        return !cls.equals("INFO") || IMPORTANT.matcher(line.message()).find();
    }

    public static String time(java.time.Instant at) {
        return at == null ? "" : TIME.format(at.atZone(ZoneId.systemDefault()));
    }
}
