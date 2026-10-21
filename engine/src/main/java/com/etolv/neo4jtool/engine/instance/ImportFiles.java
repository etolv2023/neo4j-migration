package com.etolv.neo4jtool.engine.instance;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
        if (files.maxMemory() != null && !files.maxMemory().isBlank()) {
            args.add("--max-off-heap-memory=" + files.maxMemory());
        }
        files.nodeFiles().forEach(file -> args.add("--nodes=" + directoryPrefix + file));
        files.relationshipFiles().forEach(file -> args.add("--relationships=" + directoryPrefix + file));
        return args;
    }
}
