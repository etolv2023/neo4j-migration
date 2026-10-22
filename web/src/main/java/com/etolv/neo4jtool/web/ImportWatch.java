package com.etolv.neo4jtool.web;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads what {@code neo4j-admin import} prints and remembers which of its steps it is in and how far that step has got.
 * It only looks: a line that does not fit is ignored and nothing here can stop the import.
 */
class ImportWatch {

    /** One step of the import, for example "(1/4) Node import". */
    record Stage(int number, int of, String name, int percent, long startedMillis) {

        long elapsedSeconds() {
            return Math.max(0, (System.currentTimeMillis() - startedMillis) / 1000);
        }

        /** Seconds left in this step at the speed so far, or -1 while there is too little to tell. */
        long etaSeconds() {
            long seconds = elapsedSeconds();
            return percent >= 5 && percent < 100 && seconds > 0 ? seconds * (100 - percent) / percent : -1;
        }
    }

    // "(1/4) Node import 2026-10-22 10:37:45.927+0000"
    private static final Pattern START = Pattern.compile("^\\((\\d+)/(\\d+)\\)\\s+(.+?)\\s+\\d{4}-\\d{2}-\\d{2}[ T]");
    // ".......... .......... .......... .......... ..........  45% ∆1ms [2s 5ms]"
    private static final Pattern PERCENT = Pattern.compile("^\\s*\\.[. ]*?\\s(\\d{1,3})%(\\s|$)");

    private volatile Stage stage;

    void accept(String line) {
        if (line == null) {
            return;
        }
        Matcher start = START.matcher(line);
        if (start.find()) {
            stage = new Stage(Integer.parseInt(start.group(1)), Integer.parseInt(start.group(2)), start.group(3), 0,
                    System.currentTimeMillis());
            return;
        }
        int percent = percentOf(line);
        Stage current = stage;
        if (percent >= 0 && current != null) {
            stage = new Stage(current.number(), current.of(), current.name(), Math.min(100, percent), current.startedMillis());
        } else if (current != null && line.contains(" COMPLETED in ") && line.startsWith(current.name())) {
            stage = new Stage(current.number(), current.of(), current.name(), 100, current.startedMillis());
        }
    }

    /** The percentage of a progress line of the import, or -1 when the line is not one. */
    static int percentOf(String line) {
        Matcher m = PERCENT.matcher(line);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    Stage stage() {
        return stage;
    }
}
