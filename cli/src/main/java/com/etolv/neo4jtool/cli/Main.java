package com.etolv.neo4jtool.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "neo4j-migration",
        mixinStandardHelpOptions = true,
        version = "neo4j-migration 0.1.0 (read-only)",
        description = "Looks at Neo4j databases and reports what a restore or split would do. Never writes to them.",
        subcommands = {SchemaCommand.class, DryRunCommand.class})
public final class Main implements Runnable {

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }

    static CommandLine commandLine() {
        CommandLine commandLine = new CommandLine(new Main());
        // Users get the message, not a stack trace. Messages from the driver and the parser are written to be read.
        commandLine.setExecutionExceptionHandler((ex, cmd, parseResult) -> {
            Throwable root = ex;
            while (root.getCause() != null && (root.getMessage() == null || root instanceof java.io.UncheckedIOException)) {
                root = root.getCause();
            }
            cmd.getErr().println("Error: " + root.getMessage());
            return 1;
        });
        return commandLine;
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
