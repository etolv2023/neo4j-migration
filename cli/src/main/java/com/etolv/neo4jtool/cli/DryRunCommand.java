package com.etolv.neo4jtool.cli;

import com.etolv.neo4jtool.engine.Json;
import com.etolv.neo4jtool.engine.conn.Neo4jClient;
import com.etolv.neo4jtool.engine.dryrun.DryRunOptions;
import com.etolv.neo4jtool.engine.dryrun.DryRunProgress;
import com.etolv.neo4jtool.engine.dryrun.DryRunReport;
import com.etolv.neo4jtool.engine.dryrun.DryRunService;
import com.etolv.neo4jtool.engine.selection.SelectionConfig;
import com.etolv.neo4jtool.engine.selection.SelectionConfigParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Exit codes: 0 clear, 10 warnings, 20 blocked (some selected ids are used by different nodes on the target), 1 error.
 */
@Command(name = "dry-run", mixinStandardHelpOptions = true,
        description = "Reports what restoring the selected nodes from the source into the target would do. Writes nothing.",
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {"0:Clear", "10:Warnings (for example relationships to nodes that do not exist)",
                "20:Blocked (selected ids are used by different nodes on the target)", "1:Error"})
final class DryRunCommand implements Callable<Integer> {

    static final class Source {
        @Option(names = "--source-uri", required = true, description = "Database the data comes from (the backup)")
        String uri;
        @Option(names = "--source-user")
        String user;
        @Option(names = "--source-password-env", defaultValue = "NEO4J_SOURCE_PASSWORD",
                description = "Environment variable with the source password (default: ${DEFAULT-VALUE})")
        String passwordEnv;
        @Option(names = "--source-database", defaultValue = "neo4j")
        String database;
    }

    static final class Target {
        @Option(names = "--target-uri", required = true, description = "Database the data would be put into (the live one)")
        String uri;
        @Option(names = "--target-user")
        String user;
        @Option(names = "--target-password-env", defaultValue = "NEO4J_TARGET_PASSWORD",
                description = "Environment variable with the target password (default: ${DEFAULT-VALUE})")
        String passwordEnv;
        @Option(names = "--target-database", defaultValue = "neo4j")
        String database;
    }

    @ArgGroup(exclusive = false, multiplicity = "1", heading = "%nSource%n")
    Source source;

    @ArgGroup(exclusive = false, multiplicity = "0..1", heading = "%nTarget (leave out when splitting into a new database)%n")
    Target target;

    @Option(names = "--selection", required = true, description = "YAML or JSON file with the selection")
    Path selectionFile;

    @Option(names = "--batch-size", defaultValue = "10000", description = "Ids looked up per query (default: ${DEFAULT-VALUE})")
    int batchSize;

    @Option(names = "--sample-size", defaultValue = "20", description = "Examples kept per report section (default: ${DEFAULT-VALUE})")
    int sampleSize;

    @Option(names = "--no-relationships", description = "Skip the relationship analysis")
    boolean noRelationships;

    @Option(names = "--out", description = "Write the full report as JSON to this file")
    Path out;

    @Override
    public Integer call() throws IOException {
        SelectionConfig config = SelectionConfigParser.parse(Files.readString(selectionFile));
        DryRunOptions options = new DryRunOptions(batchSize, sampleSize, !noRelationships);
        ConnectionOptions sourceConnection = connection(source.uri, source.user, source.passwordEnv, source.database);
        try (Neo4jClient sourceClient = sourceConnection.open();
             Neo4jClient targetClient = target == null ? null
                     : connection(target.uri, target.user, target.passwordEnv, target.database).open()) {
            DryRunReport report = new DryRunService().run(sourceClient, targetClient, config, options, new ConsoleProgress());
            System.err.println();
            if (out != null) {
                Files.writeString(out, Json.writePretty(report));
                System.out.println("Full report written to " + out);
            }
            System.out.print(ReportText.render(report));
            return switch (report.gate()) {
                case CLEAR -> 0;
                case WARN -> 10;
                case BLOCKED -> 20;
            };
        }
    }

    private static ConnectionOptions connection(String uri, String user, String passwordEnv, String database) {
        ConnectionOptions c = new ConnectionOptions();
        c.uri = uri;
        c.user = user;
        c.passwordEnv = passwordEnv;
        c.database = database;
        return c;
    }

    private static final class ConsoleProgress implements DryRunProgress {
        private long lastPrint;

        @Override
        public void phase(String name) {
            System.err.println("\n" + name);
        }

        @Override
        public void progress(long done, long total) {
            long now = System.currentTimeMillis();
            if (now - lastPrint > 1000 || done == total) {
                lastPrint = now;
                System.err.printf("\r  %,d / %s", done, total < 0 ? "?" : String.format("%,d", total));
            }
        }
    }
}
