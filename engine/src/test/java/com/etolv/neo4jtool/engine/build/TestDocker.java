package com.etolv.neo4jtool.engine.build;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Runs the docker command for test set-up and clean-up. */
final class TestDocker {

    private TestDocker() {
    }

    /** @return the output of {@code docker <args>} (stdout and stderr together), whatever the exit code */
    static String run(String... args) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();
            return output;
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
