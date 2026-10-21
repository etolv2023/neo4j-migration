package com.etolv.neo4jtool.engine.instance;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV files for {@code neo4j-admin database import full --id-type=actual}, in one directory.
 *
 * @param arrayDelimiter the character that separates the elements of a list inside one cell
 */
public record ImportFiles(Path directory, List<String> nodeFiles, List<String> relationshipFiles, char arrayDelimiter) {

    /**
     * The import arguments. The database name has to come before the file options, which would otherwise take it for a
     * file. {@code --bad-tolerance=0} makes the first unusable row stop the import instead of being skipped.
     */
    static List<String> arguments(String database, String directoryPrefix, String reportFile, ImportFiles files) {
        List<String> args = new ArrayList<>(List.of("database", "import", "full", database, "--id-type=actual",
                "--bad-tolerance=0", "--overwrite-destination", "--multiline-fields=true", "--input-encoding=UTF-8",
                "--array-delimiter=" + files.arrayDelimiter(), "--report-file=" + reportFile));
        files.nodeFiles().forEach(file -> args.add("--nodes=" + directoryPrefix + file));
        files.relationshipFiles().forEach(file -> args.add("--relationships=" + directoryPrefix + file));
        return args;
    }
}
