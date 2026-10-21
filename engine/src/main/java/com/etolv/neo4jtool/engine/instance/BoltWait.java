package com.etolv.neo4jtool.engine.instance;

import java.time.Duration;
import java.util.function.Supplier;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;

/** Waits until a server accepts Bolt connections. */
final class BoltWait {

    private BoltWait() {
    }

    /**
     * @param failure returns a message when the server has already died (so waiting is pointless), otherwise null
     */
    static void until(String uri, Duration timeout, Supplier<String> failure) {
        long deadline = System.nanoTime() + timeout.toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            String died = failure.get();
            if (died != null) {
                throw new InstanceException("The temporary Neo4j server stopped while starting:\n" + died);
            }
            try (Driver driver = GraphDatabase.driver(uri, AuthTokens.none())) {
                driver.verifyConnectivity();
                return;
            } catch (RuntimeException e) {
                last = e;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InstanceException("Interrupted while waiting for Neo4j to start", e);
            }
        }
        throw new InstanceException("The temporary Neo4j server did not accept connections within " + timeout.toMinutes()
                + " minutes" + (last == null ? "" : ": " + last.getMessage()));
    }
}
