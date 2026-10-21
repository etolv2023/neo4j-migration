package com.etolv.neo4jtool.engine.build;

import com.etolv.neo4jtool.engine.conn.ConnectionSettings;
import com.etolv.neo4jtool.engine.conn.Neo4jClient;
import com.etolv.neo4jtool.engine.instance.ImportFiles;
import com.etolv.neo4jtool.engine.instance.InstanceRunner;
import com.etolv.neo4jtool.engine.instance.InstanceRunner.RunningInstance;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Builds a database from CSV files with the node ids kept, finishes it on a temporary server, compares it with what was
 * exported and checks the store. Shared by the split and the merge. If anything fails the caller discards the files.
 */
final class NewDatabase {

    /** Properties the import could not carry and the database they are read from again. */
    record FixupSource(Neo4jClient client, Fixups fixups) {
    }

    private NewDatabase() {
    }

    /**
     * @param csv   the directory the export wrote to
     * @param data  an empty directory the runner may fill with the new store
     * @param schema indexes and constraints to create, as statements (may be empty)
     */
    static void build(InstanceRunner runner, Path csv, Path data, String database, List<String> nodeFiles,
                      List<String> relationshipFiles, List<FixupSource> fixups, List<String> schema,
                      Verifier.Expected expected, boolean check, Phases phases, Consumer<String> log) {
        phases.start("Building the new database with neo4j-admin import (ids kept)");
        runner.importDatabase(data, database, new ImportFiles(csv, nodeFiles, relationshipFiles, ValueCodec.ARRAY_DELIMITER), log);

        phases.start("Finishing the new database on a temporary server");
        try (RunningInstance server = runner.serveDataDirectory(data, database, log)) {
            try (DatabaseWriter writer = new DatabaseWriter(server.boltUri(), database)) {
                for (FixupSource source : fixups) {
                    if (source.fixups().size() > 0) {
                        log.accept("Writing " + source.fixups().size() + " properties the import cannot carry");
                        writer.applyFixups(source.client(), source.fixups());
                    }
                }
                if (!schema.isEmpty()) {
                    log.accept("Creating " + schema.size() + " indexes and constraints");
                    writer.createSchema(schema);
                }
            }
            phases.start("Comparing the new database with the exported data");
            try (Neo4jClient built = new Neo4jClient(new ConnectionSettings(server.boltUri(), null, null, database))) {
                built.verify();
                Verifier.verify(built, expected);
            }
            phases.start("Stopping the temporary server");
        }

        if (check) {
            phases.start("Checking the store (neo4j-admin database check)");
            UnusedRecords unused = new UnusedRecords(log);
            runner.checkDatabase(data, database, unused);
            unused.summarize();
        }
    }

    /**
     * The new store has a slot for every id up to the highest one, and the ids that are not in it stay unused. The
     * check lists each of them as a warning, which can be thousands of lines; they are counted instead of logged.
     */
    private static final class UnusedRecords implements Consumer<String> {
        private final Consumer<String> log;
        private long count;

        UnusedRecords(Consumer<String> log) {
            this.log = log;
        }

        @Override
        public void accept(String line) {
            if (line.contains("is not in use but the id is not up for reuse")) {
                count++;
            } else if (!(line.stripLeading().startsWith("Node[") && line.contains("used=false"))) {
                log.accept(line);
            }
        }

        void summarize() {
            if (count > 0) {
                log.accept(count + " unused node slots were reported by the check. That is expected: ids that are not in the "
                        + "new database leave gaps.");
            }
        }
    }
}
