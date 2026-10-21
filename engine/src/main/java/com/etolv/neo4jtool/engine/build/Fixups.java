package com.etolv.neo4jtool.engine.build;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Node properties that the CSV import cannot carry exactly (empty lists, date-times with a named time zone). The export
 * writes each one, with its value, to a file of JSON lines; they are set on the finished database afterwards. Only the
 * count and the file names are kept in memory.
 */
public final class Fixups {

    private final long count;
    private final List<Path> files;

    public Fixups(long count, List<Path> files) {
        this.count = count;
        this.files = List.copyOf(files);
    }

    static Fixups none() {
        return new Fixups(0, List.of());
    }

    static Fixups of(List<Fixups> parts) {
        long total = 0;
        List<Path> files = new ArrayList<>();
        for (Fixups part : parts) {
            total += part.count;
            files.addAll(part.files);
        }
        return new Fixups(total, files);
    }

    public long size() {
        return count;
    }

    public List<Path> files() {
        return files;
    }
}
