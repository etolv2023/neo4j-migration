package com.etolv.neo4jtool.engine.instance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Runs external commands and passes their output to a log as it appears. */
final class Commands {

    private static final int TAIL_LINES = 25;

    private Commands() {
    }

    /** Runs a command to the end. Throws with the last lines of output when the exit code is not 0. */
    static void run(List<String> command, Map<String, String> environment, Path stdin, Consumer<String> log) {
        log.accept("$ " + String.join(" ", command));
        try {
            Process process = start(command, environment, stdin);
            Deque<String> tail = new ArrayDeque<>();
            try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = out.readLine()) != null) {
                    if (!line.isBlank()) {
                        log.accept(line);
                        tail.addLast(line);
                        if (tail.size() > TAIL_LINES) {
                            tail.removeFirst();
                        }
                    }
                }
            }
            int exit = process.waitFor();
            if (exit != 0) {
                throw new InstanceException(command.get(0) + " failed with exit code " + exit + ":\n" + String.join("\n", tail));
            }
        } catch (IOException e) {
            throw new InstanceException("Could not run " + command.get(0) + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InstanceException("Interrupted while running " + command.get(0), e);
        }
    }

    /** Runs a command and returns its output, without logging it. */
    static String output(List<String> command) {
        try {
            Process process = start(command, Map.of(), null);
            String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.waitFor() != 0) {
                throw new InstanceException(command.get(0) + " failed:\n" + text);
            }
            return text;
        } catch (IOException e) {
            throw new InstanceException("Could not run " + command.get(0) + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InstanceException("Interrupted while running " + command.get(0), e);
        }
    }

    static Process start(List<String> command, Map<String, String> environment, Path stdin) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(environment);
        if (stdin != null) {
            builder.redirectInput(stdin.toFile());
        }
        return builder.start();
    }
}
