package com.etolv.neo4jtool.engine.build;

import com.etolv.neo4jtool.engine.dryrun.DryRunProgress;
import com.etolv.neo4jtool.engine.selection.SelectionConfig;
import java.nio.file.Path;
import java.util.List;

/**
 * Bulk reads done by a separate process that has the store open and uses the database kernel directly, much faster than
 * asking over Bolt. The results are the same as the Bolt readers' (the tests compare them), so the callers can use
 * either. Closing it ends the process and releases the store.
 */
public interface FastReader extends AutoCloseable {

    /** The ids of the nodes a selection matches. */
    IdSet select(SelectionConfig selection, int workers, DryRunProgress progress);

    /** The ids of all nodes. */
    IdSet allIds(int workers, DryRunProgress progress);

    /** Same as {@code new CsvExporter(...).export()}, run inside the reader process. */
    CsvExporter.Export export(IdSet ids, List<CsvExporter.Rule> rules, String prefix, Path directory, int batchSize, int workers,
                              DryRunProgress progress);

    /** Same as {@link Verifier#verify}, run inside the reader process on the database it holds open. */
    void verify(Verifier.Expected expected, int batchSize, int workers, DryRunProgress progress);

    /** Opens a store for fast reading. */
    interface Factory {

        /**
         * @param dataDirectory the data directory of a stopped server (or of a database that was just built)
         * @param bolt          also serve Bolt for Cypher questions
         */
        FastReader open(Path dataDirectory, String database, boolean bolt, java.util.function.Consumer<String> log);
    }

    /** The Bolt address, when {@code bolt} was asked for. */
    String boltUri();

    @Override
    void close();
}
