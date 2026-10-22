package com.etolv.neo4jtool.web;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

    /** A step of the import that has finished and how long it took. */
    record Done(String name, long seconds) {
    }

    private volatile Stage stage;
    /** When neo4j-admin said what it has to work with (its first words), and when it began to read rows; 0 = not yet. */
    private volatile long launchedMillis;
    private volatile long preparedMillis;
    private final List<Done> finished = new CopyOnWriteArrayList<>();

    void accept(String line) {
        if (line == null) {
            return;
        }
        if (line.startsWith("Available resources:")) {
            launchedMillis = System.currentTimeMillis();
            preparedMillis = 0;
            finished.clear();
            stage = null;
            return;
        }
        if (line.startsWith("Import starting") && launchedMillis > 0 && preparedMillis == 0) {
            preparedMillis = System.currentTimeMillis();
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
            finished.add(new Done(current.name(), Math.max(0, (System.currentTimeMillis() - current.startedMillis()) / 1000)));
        }
    }

    /**
     * Seconds neo4j-admin has been getting ready for, while it has not begun to read rows: it opens every layout of the
     * export on its own first and says nothing meanwhile. -1 when it is not doing that.
     */
    long preparingSeconds() {
        long launched = launchedMillis;
        return launched > 0 && preparedMillis == 0 ? Math.max(0, (System.currentTimeMillis() - launched) / 1000) : -1;
    }

    /** How long that took, or -1 while it has not finished (or never started). */
    long preparedSeconds() {
        return launchedMillis > 0 && preparedMillis > 0 ? Math.max(0, (preparedMillis - launchedMillis) / 1000) : -1;
    }

    /** The steps of the import that have finished, in order. */
    List<Done> finished() {
        return List.copyOf(finished);
    }

    /** "33 min 12 s", "45 s", "1 h 2 min". */
    static String text(long seconds) {
        if (seconds < 60) {
            return seconds + " s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " min " + seconds % 60 + " s";
        }
        return minutes / 60 + " h " + minutes % 60 + " min";
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
