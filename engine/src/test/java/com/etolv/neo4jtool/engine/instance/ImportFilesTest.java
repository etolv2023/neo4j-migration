package com.etolv.neo4jtool.engine.instance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportFilesTest {

    @TempDir
    Path directory;

    @Test
    void theCommandLineStaysShortHoweverManyLayoutsThereAre() throws Exception {
        List<String> nodes = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            nodes.add("target-nodes-h" + i + ".csv");
            nodes.add("target-nodes-p" + i + "-w0.csv");
            nodes.add("target-nodes-p" + i + "-w1.csv");
        }
        List<String> relationships = List.of("target-relationships-h0.csv", "target-relationships-p0-w0.csv");

        List<String> command = ImportFiles.arguments("neo4j", "/import/", "/tmp/import.report",
                new ImportFiles(directory, nodes, relationships, ';'));

        assertThat(String.join(" ", command).length()).isLessThan(1_000);
        assertThat(command).contains("@/import/import.args");
        assertThat(command).noneMatch(argument -> argument.startsWith("--nodes=") || argument.startsWith("--relationships="));

        List<String> lines = Files.readAllLines(directory.resolve("import.args"));
        assertThat(lines).hasSize(20_001);
        assertThat(lines.get(0)).isEqualTo("\"--nodes=/import/target-nodes-h0.csv,/import/target-nodes-p0-w[0-9]+[.]csv\"");
        assertThat(lines.get(20_000)).isEqualTo(
                "\"--relationships=/import/target-relationships-h0.csv,/import/target-relationships-p0-w[0-9]+[.]csv\"");
    }
}
