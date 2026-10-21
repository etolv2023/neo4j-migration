package com.etolv.neo4jtool.engine.instance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.Consumer;

/** A directory under the work directory that belongs to one throw-away database. */
final class Workspace {

    private final Path root;

    private Workspace(Path root) {
        this.root = root;
    }

    static Workspace create(Path workDir, String prefix) {
        try {
            Files.createDirectories(workDir);
            Path root = Files.createTempDirectory(workDir, prefix + "-");
            // Containers may run as another user and must be able to write into the directories made here.
            Files.setPosixFilePermissions(root, java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"));
            return new Workspace(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create a work directory in " + workDir, e);
        }
    }

    Path root() {
        return root;
    }

    Path directory(String name) {
        try {
            Path dir = Files.createDirectories(root.resolve(name));
            Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"));
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Deletes everything. Files written by a container can belong to another user; {@code fallback} gets one more try
     * in that case (for Docker, a container that removes them).
     */
    void delete(Consumer<Path> fallback) {
        try {
            deleteTree(root);
        } catch (IOException | UncheckedIOException e) {
            fallback.accept(root);
            try {
                deleteTree(root);
            } catch (IOException e2) {
                throw new UncheckedIOException("Could not remove " + root + ". Delete it by hand.", e2);
            }
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
