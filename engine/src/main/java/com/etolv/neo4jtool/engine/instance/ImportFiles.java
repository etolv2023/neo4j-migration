package com.etolv.neo4jtool.engine.instance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CSV files for {@code neo4j-admin database import full --id-type=actual}, in one directory.
 *
 * @param arrayDelimiter the character that separates the elements of a list inside one cell
 * @param threads        worker threads of the import; 0 lets neo4j-admin choose (one per core)
 * @param maxMemory      limit for the import's memory, for example "8g"; empty lets neo4j-admin choose
 */
public record ImportFiles(Path directory, List<String> nodeFiles, List<String> relationshipFiles, char arrayDelimiter,
                          int threads, String maxMemory) {

    public ImportFiles(Path directory, List<String> nodeFiles, List<String> relationshipFiles, char arrayDelimiter) {
        this(directory, nodeFiles, relationshipFiles, arrayDelimiter, 0, "");
    }

    /**
     * The import arguments. The database name has to come before the file options, which would otherwise take it for a
     * file. {@code --bad-tolerance=0} makes the first unusable row stop the import instead of being skipped.
     */
    static List<String> arguments(String database, String directoryPrefix, String reportFile, ImportFiles files) {
        List<String> args = new ArrayList<>(List.of("database", "import", "full", database, "--id-type=actual",
                "--bad-tolerance=0", "--overwrite-destination", "--multiline-fields=true", "--input-encoding=UTF-8",
                "--array-delimiter=" + files.arrayDelimiter(), "--report-file=" + reportFile));
        if (files.threads() > 0) {
            args.add("--threads=" + files.threads());
        }
        args.add("--max-off-heap-memory=" + offHeap(files, ImportResources.detect()));
        // The files go into an arguments file next to the CSV files: a database can have thousands of layouts, and a
        // command line is limited to about 2 MB ("Argument list too long"). neo4j-admin reads "@file" itself.
        List<String> lines = new ArrayList<>();
        groups(files.nodeFiles(), directoryPrefix).forEach(group -> lines.add("--nodes=" + group));
        groups(files.relationshipFiles(), directoryPrefix).forEach(group -> lines.add("--relationships=" + group));
        try {
            Files.write(files.directory().resolve(ARGUMENTS_FILE), lines.stream().map(line -> "\"" + line + "\"").toList(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the import arguments file: " + e.getMessage(), e);
        }
        args.add("@" + directoryPrefix + ARGUMENTS_FILE);
        return args;
    }

    static final String ARGUMENTS_FILE = "import.args";

    /** The off-heap memory of the import: what the settings say, or what fits this machine. */
    static String offHeap(ImportFiles files, ImportResources.Plan automatic) {
        return files.maxMemory() != null && !files.maxMemory().isBlank() ? files.maxMemory() : automatic.offHeap();
    }

    private static final Pattern HEADER = Pattern.compile("(.*-)h(\\d+)\\.csv");
    private static final Pattern PART = Pattern.compile(".*-p\\d+-w\\d+\\.csv");

    /**
     * One import argument per layout. The export writes the header of a layout in {@code <prefix>-h<n>.csv} and its rows,
     * one file per worker, in {@code <prefix>-p<n>-w<worker>.csv}; the importer reads files that match a regular expression
     * as one, so the number of arguments is the number of layouts, not that number times the workers (a command line
     * can only be so long). A file that is neither a header nor a part is passed as it is.
     */
    static List<String> groups(List<String> files, String directoryPrefix) {
        List<String> groups = new ArrayList<>();
        for (String file : files) {
            Matcher header = HEADER.matcher(file);
            if (header.matches()) {
                groups.add(directoryPrefix + file + "," + directoryPrefix + header.group(1) + "p" + header.group(2) + "-w[0-9]+[.]csv");
            } else if (!PART.matcher(file).matches()) {
                groups.add(directoryPrefix + file);
            }
        }
        return groups;
    }
}
