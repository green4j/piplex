/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.init;

import io.github.green4j.piplex.Environment;
import io.github.green4j.piplex.init.Estate.Controller;
import io.github.green4j.piplex.init.Estate.Grants;
import io.github.green4j.piplex.init.Estate.Operator;
import io.github.green4j.piplex.init.Estate.Transport;
import io.github.green4j.piplex.init.Estate.Work;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One description in, one directory out. */
class OutputTest {

    @Test
    void writesEveryControllerItsOwnEverything(@TempDir final Path into) throws IOException {
        Output.write(estate(), into);

        for (final String ownerId : List.of("euc1-blue", "euc1-green")) {
            final Path directory = into.resolve("controllers").resolve(ownerId);
            assertTrue(Files.readString(directory.resolve("jenkins.yaml"))
                    .contains("ownerId: \"" + ownerId + '"'));
            for (final String job : Jobs.ALL) {
                assertTrue(Files.exists(directory.resolve("jobs").resolve(job)),
                        job + " is missing from " + ownerId);
            }
        }
        assertTrue(Files.exists(into.resolve("acl").resolve("piplex.conf")));
        assertTrue(Files.exists(into.resolve("pipelines").resolve("eod.groovy")));
        assertTrue(Files.exists(into.resolve("pipelines").resolve("corrections.groovy")));
        assertTrue(Files.exists(into.resolve(RunNotes.FILE)));
    }

    // The second run of the tool is not the first one again, and this is the whole of why.
    @Test
    void writesADescriptionItCanReadBack(@TempDir final Path into) throws IOException {
        final Estate estate = estate();

        Output.write(estate, into);

        assertEquals(estate, Estates.read(into.resolve(Estates.FILE)));
    }

    // Nothing generated may carry a secret, because this directory is committed somewhere.
    @Test
    void writesNoSecretAnywhereInTheTree(@TempDir final Path into) throws IOException {
        Output.write(estate(), into);

        try (var files = Files.walk(into)) {
            for (final Path file : files.filter(Files::isRegularFile).toList()) {
                final String content = Files.readString(file);
                assertFalse(content.contains("BEGIN PRIVATE KEY"), file.toString());
                for (final String line : content.split("\n")) {
                    if (line.contains("Password:") || line.contains("token:")) {
                        assertTrue(line.contains("${"), file + ": " + line);
                    }
                }
            }
        }
    }

    @Test
    void replacesTheWholeGeneratedTreeOnRegeneration(@TempDir final Path into) throws IOException {
        Output.write(estate(), into);
        Files.writeString(into.resolve("left-by-an-old-generator"), "stale");

        Output.write(replacement(), into);

        assertFalse(Files.exists(into.resolve("controllers").resolve("euc1-blue")));
        assertFalse(Files.exists(into.resolve("pipelines").resolve("eod.groovy")));
        assertFalse(Files.exists(into.resolve("left-by-an-old-generator")));
        assertTrue(Files.exists(into.resolve("controllers").resolve("euc1-red")
                .resolve("jenkins.yaml")));
        assertTrue(Files.exists(into.resolve("pipelines").resolve("new-day.groovy")));
    }

    @Test
    void restoresTheOldTreeWhenTheCommitCannotInstallTheNewOne(
            @TempDir final Path directory) throws IOException {
        final Path target = directory.resolve("out");
        Files.createDirectories(target);
        Files.writeString(target.resolve("still-here"), "old");

        assertThrows(IOException.class,
                () -> Output.replace(directory.resolve("missing-staging"), target));

        assertEquals("old", Files.readString(target.resolve("still-here")));
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().contains(".backup-")),
                    "A failed replacement left its backup behind");
        }
    }

    private static Estate estate() {
        return new Estate(Environment.of("prod"),
                List.of(new Controller("euc1-blue", "piplex-euc1-blue"),
                        new Controller("euc1-green", "piplex-euc1-green")),
                "n1=10.0.0.11:7101", Transport.MTLS, Estate.SECRETS,
                new Operator("piplex-ops", "piplex-ops-cert"),
                List.of(Work.of("eod", "nightly", "eod-owner", null, "data/euc1"),
                        Work.of("corrections", "nightly", "eod-owner", null, "data/corrections")),
                Grants.PER_KEY);
    }

    private static Estate replacement() {
        return new Estate(Environment.of("prod"),
                List.of(new Controller("euc1-red", "piplex-euc1-red")),
                "n1=10.0.0.11:7101", Transport.MTLS, Estate.SECRETS,
                new Operator("piplex-ops", "piplex-ops-cert"),
                List.of(Work.of("new-day", "new-day-switch", "new-day-owner", null, "data/euc1")),
                Grants.PER_KEY);
    }
}
