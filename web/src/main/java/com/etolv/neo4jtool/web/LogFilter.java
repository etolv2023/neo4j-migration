package com.etolv.neo4jtool.web;

import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * What a job prints, made readable before it is kept: the list of thousands of CSV files that the import prints is one
 * line, the progress of the import is one line per ten percent (the dots are dropped), and empty lines are dropped. The
 * import watch sees every line first, so its bar does not depend on what is kept. Nothing here can stop a job: the watch
 * is guarded and the lines go on to the log as they are when anything unexpected happens.
 */
final class LogFilter implements Consumer<String> {

    private static final Pattern CSV_PATH = Pattern.compile("^\\s+\\S+\\.csv\\s*$");

    private final Consumer<String> next;
    private final ImportWatch watch;
    private int files;

    LogFilter(Consumer<String> next, ImportWatch watch) {
        this.next = next;
        this.watch = watch;
    }

    @Override
    public synchronized void accept(String line) {
        try {
            if (line == null) {
                return;
            }
            watch.accept(line);
            if (line.startsWith("Import starting") && watch.preparedSeconds() >= 0) {
                flushFiles();
                next.accept(line);
                next.accept("  The importer got ready for " + ImportWatch.text(watch.preparedSeconds())
                        + " before it read the first row: it opens every layout of the export on its own, so fewer layouts mean less waiting");
                return;
            }
            if (line.isBlank()) {
                return;
            }
            if (CSV_PATH.matcher(line).matches()) {
                files++;
                return;
            }
            flushFiles();
            int percent = ImportWatch.percentOf(line);
            if (percent >= 0) {
                if (percent % 10 == 0) {
                    next.accept("  " + line.replaceFirst("^[. ]+", ""));
                }
                return;
            }
        } catch (RuntimeException e) {
            // the line is kept as it is
        }
        next.accept(line);
    }

    private void flushFiles() {
        if (files > 0) {
            int count = files;
            files = 0;
            next.accept("  " + count + " CSV files");
        }
    }
}
