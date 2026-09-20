/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.init;

import io.github.green4j.piplex.init.Estate.Controller;
import io.github.green4j.piplex.init.Estate.Work;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Everything the estate implies, written out as one directory.
 *
 * <p>The directory is the point. Four kinds of artifact that have to name the same keys -- an ACL,
 * a controller configuration, five operator jobs, a pipeline block -- land together, from one
 * description, at one moment. Each carries a line saying it was generated and where from, because
 * the way a set like this stops agreeing is that somebody edits one of them.
 */
public final class Output {

    private Output() {
    }

    /**
     * @param estate what was decided
     * @param into   the generator-owned directory to replace, created if it is not there
     * @return every file written, in the order written
     * @throws IOException if anything cannot be written
     */
    public static List<Path> write(final Estate estate, final Path into) throws IOException {
        final List<String> problems = estate.problems();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Estate is invalid: " + String.join("; ", problems));
        }

        final Path target = into.toAbsolutePath().normalize();
        if (target.getParent() == null || target.getFileName() == null) {
            throw new IllegalArgumentException("Output directory must not be a filesystem root");
        }
        managed(target);
        Files.createDirectories(target.getParent());
        final Path staging = Files.createTempDirectory(target.getParent(),
                "." + target.getFileName() + ".staging-");
        boolean installed = false;
        try {
            final List<Path> staged = writeTree(estate, staging);
            final List<Path> relative = staged.stream().map(staging::relativize).toList();
            final Path backup = replace(staging, target);
            installed = true;
            if (backup != null) {
                deleteTree(backup);
            }
            return relative.stream().map(into::resolve).toList();
        } catch (final IOException | RuntimeException failure) {
            if (!installed) {
                try {
                    deleteTree(staging);
                } catch (final IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    private static List<Path> writeTree(final Estate estate, final Path into) throws IOException {
        final List<Path> written = new ArrayList<>();
        written.add(put(inside(into, Estates.FILE), Estates.render(estate)));
        written.add(put(inside(into, RunNotes.FILE), RunNotes.render(estate)));
        written.add(put(inside(into, "acl", "piplex.conf"), Acl.render(estate)));
        for (final Controller controller : estate.controllers()) {
            final Path directory = inside(into, "controllers", controller.ownerId());
            written.add(put(directory.resolve("jenkins.yaml"), Casc.render(estate, controller)));
            for (final String job : Jobs.ALL) {
                written.add(put(directory.resolve("jobs").resolve(job),
                        Jobs.render(estate, controller, job)));
            }
        }
        // One block per work, not one per work and controller: the only part that differs between
        // controllers is activeWhenValue, and the notes say which line that is.
        final Controller first = estate.controllers().isEmpty()
                ? new Controller("", "") : estate.controllers().get(0);
        for (final Work work : estate.work()) {
            written.add(put(inside(into, "pipelines", work.key() + ".groovy"),
                    Pipelines.render(work, first)));
        }
        return written;
    }

    private static Path inside(final Path root, final String... parts) {
        Path path = root;
        for (final String part : parts) {
            path = path.resolve(part);
        }
        final Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("Generated path leaves the output directory: " + path);
        }
        return normalized;
    }

    private static void managed(final Path target) throws IOException {
        if (!Files.exists(target)) {
            return;
        }
        if (!Files.isDirectory(target)) {
            throw new IllegalArgumentException("Output path is not a directory: " + target);
        }
        try (Stream<Path> children = Files.list(target)) {
            if (children.findAny().isPresent()
                    && (!Files.isRegularFile(target.resolve(Estates.FILE))
                    || !Files.isRegularFile(target.resolve(RunNotes.FILE)))) {
                throw new IllegalArgumentException("Output directory is not a piplex-init tree: "
                        + target);
            }
        }
    }

    static Path replace(final Path staging, final Path target) throws IOException {
        Path backup = null;
        if (Files.exists(target)) {
            backup = target.resolveSibling("." + target.getFileName() + ".backup-" + UUID.randomUUID());
            move(target, backup);
        }
        try {
            move(staging, target);
            return backup;
        } catch (final IOException failure) {
            if (backup != null) {
                try {
                    move(backup, target);
                } catch (final IOException restore) {
                    failure.addSuppressed(restore);
                }
            }
            throw failure;
        }
    }

    private static void move(final Path from, final Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException unsupported) {
            Files.move(from, to);
        }
    }

    private static void deleteTree(final Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            final List<Path> deepestFirst = paths
                    .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                    .toList();
            for (final Path path : deepestFirst) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Path put(final Path file, final String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
