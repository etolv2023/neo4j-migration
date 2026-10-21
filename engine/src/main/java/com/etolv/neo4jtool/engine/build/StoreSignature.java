package com.etolv.neo4jtool.engine.build;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * A fingerprint of the files of one database in a stopped server's data directory (names, sizes, modification times).
 * If it is different later, something wrote to the database in the meantime, for example because the server was started.
 */
final class StoreSignature {

    private StoreSignature() {
    }

    static String of(Path dataDirectory, String database) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path root : List.of(dataDirectory.resolve("databases").resolve(database),
                    dataDirectory.resolve("transactions").resolve(database))) {
                digest.update(("[" + root.getParent().getFileName() + "]").getBytes(StandardCharsets.UTF_8));
                if (!Files.isDirectory(root)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(root)) {
                    for (Path file : (Iterable<Path>) files.sorted()::iterator) {
                        String line = root.relativize(file) + " " + (Files.isRegularFile(file) ? Files.size(file) : -1) + " "
                                + Files.getLastModifiedTime(file).toMillis() + "\n";
                        digest.update(line.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
