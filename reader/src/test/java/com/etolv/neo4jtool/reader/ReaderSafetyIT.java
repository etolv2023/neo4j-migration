package com.etolv.neo4jtool.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etolv.neo4jtool.engine.build.BuildException;
import com.etolv.neo4jtool.engine.build.IdSet;
import com.etolv.neo4jtool.engine.build.StoreSignature;
import com.etolv.neo4jtool.engine.dryrun.DryRunProgress;
import com.etolv.neo4jtool.engine.fast.ReaderProcess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading a stopped data directory in place must not change a single byte of it, and must refuse one that needs recovery. */
class ReaderSafetyIT {

    @TempDir
    Path temp;

    private ReaderProcess.Settings settings() {
        return new ReaderProcess.Settings(System.getProperty("java.class.path"), "1g", 256L * 1024 * 1024, temp.resolve("scratch"),
                Duration.ofMinutes(3));
    }

    @Test
    void aCleanStoreIsReadAndItsFilesAreNotChanged() throws Exception {
        Path data = EmbeddedFixture.create(temp.resolve("home"), new String[] {
                "UNWIND range(1, 500) AS i CREATE (:Service {org_id: i % 2, name: 'n' + i})",
                "CREATE INDEX service_org FOR (n:Service) ON (n.org_id)"}, Map.of());
        String before = StoreSignature.of(data, "neo4j");
        List<String> log = new ArrayList<>();

        try (ReaderProcess reader = ReaderProcess.start(settings(), data, "neo4j", true, log::add)) {
            IdSet all = reader.allIds(2, DryRunProgress.NONE);
            assertThat(all.size()).isEqualTo(500);
        }

        assertThat(StoreSignature.of(data, "neo4j")).as("the files of the source").isEqualTo(before);
        try (var scratch = Files.list(temp.resolve("scratch"))) {
            assertThat(scratch.filter(p -> p.getFileName().toString().startsWith("reader-"))).as("scratch left behind").isEmpty();
        }
    }

    @Test
    void aStoreThatWasNotShutDownCleanlyIsRefusedAndNotTouched() throws Exception {
        Path home = temp.resolve("crashed");
        Process crash = new ProcessBuilder(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--add-opens=java.base/java.nio=ALL-UNNAMED", "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
                "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
                CrashMain.class.getName(), home.toString())).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertThat(crash.waitFor()).as("the child halts itself").isNotZero();
        Path data = home.resolve("data");
        String before = StoreSignature.of(data, "neo4j");

        assertThatThrownBy(() -> ReaderProcess.start(settings(), data, "neo4j", false, line -> { }))
                .isInstanceOf(BuildException.class).hasMessageContaining("not shut down cleanly");

        assertThat(StoreSignature.of(data, "neo4j")).as("the files of the crashed source").isEqualTo(before);
    }
}
