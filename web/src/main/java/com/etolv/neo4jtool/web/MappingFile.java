package com.etolv.neo4jtool.web;

import com.etolv.neo4jtool.engine.Json;
import com.etolv.neo4jtool.engine.build.RenumberPlanner;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The whole table of new ids of one dry run, as it is stored (gzip, one JSON object per line) and as the two downloads. Rows
 * are read and written one at a time, so a table of millions of rows is never held as objects.
 */
final class MappingFile {

    static final String CSV_HEADER = "old_id,new_id,labels,org,target_labels,target_org\n";

    private MappingFile() {
    }

    /** Collects the rows while the dry run makes them. */
    static final class Collector implements RenumberPlanner.MappingSink {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final Writer out;
        private long entries;

        Collector() {
            try {
                out = new OutputStreamWriter(new GZIPOutputStream(bytes, 1 << 16), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public synchronized void accept(List<RenumberPlanner.Entry> batch) {
            try {
                for (RenumberPlanner.Entry entry : batch) {
                    out.write(Json.write(entry));
                    out.write('\n');
                    entries++;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        long entries() {
            return entries;
        }

        byte[] finish() {
            try {
                out.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return bytes.toByteArray();
        }
    }

    static void writeCsv(byte[] stored, OutputStream target) throws IOException {
        Writer out = new OutputStreamWriter(target, StandardCharsets.UTF_8);
        out.write(CSV_HEADER);
        forEach(stored, entry -> out.write(RenumberView.csvRow(entry)));
        out.flush();
    }

    /** A JSON array with one object per row. */
    static void writeJson(byte[] stored, OutputStream target) throws IOException {
        Writer out = new OutputStreamWriter(target, StandardCharsets.UTF_8);
        out.write("[");
        boolean[] first = {true};
        forEachLine(stored, line -> {
            out.write(first[0] ? "\n" : ",\n");
            first[0] = false;
            out.write(line);
        });
        out.write(first[0] ? "]\n" : "\n]\n");
        out.flush();
    }

    private interface Rows<T> {
        void accept(T row) throws IOException;
    }

    private static void forEach(byte[] stored, Rows<RenumberPlanner.Entry> rows) throws IOException {
        forEachLine(stored, line -> rows.accept(Json.read(line, RenumberPlanner.Entry.class)));
    }

    private static void forEachLine(byte[] stored, Rows<String> rows) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(stored), 1 << 16);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    rows.accept(line);
                }
            }
        }
    }
}
