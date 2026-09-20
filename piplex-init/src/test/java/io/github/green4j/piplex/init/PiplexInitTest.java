/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.init;

import io.github.green4j.discas.common.cli.GetOpts;
import io.github.green4j.piplex.Environment;
import io.github.green4j.piplex.init.Estate.Grants;
import io.github.green4j.piplex.init.Estate.Operator;
import io.github.green4j.piplex.init.Estate.Transport;
import io.github.green4j.piplex.init.Estate.Work;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The non-interactive command is a deployment gate, including its process exit status. */
class PiplexInitTest {

    @Test
    void refusesAnInvalidEstateBeforeWritingAnything(@TempDir final Path directory) throws Exception {
        final Path into = directory.resolve("out");
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final ByteArrayOutputStream errors = new ByteArrayOutputStream();

        final int status;
        try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
                PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
            status = PiplexInit.write(invalid(), into, out, err);
        }

        assertEquals(2, status);
        assertFalse(Files.exists(into), "An invalid estate created its output directory");
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("Nothing written."));
    }

    @Test
    void quietProcessReturnsTwoForAnInvalidEstate(@TempDir final Path directory) throws Exception {
        final Path description = directory.resolve("invalid.properties");
        Files.writeString(description, """
                environment = prod
                controllers =
                work =
                nodes =
                transport = allowall
                grants = per-environment
                """, StandardCharsets.UTF_8);
        final Path into = directory.resolve("out");
        final Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classPath(PiplexInit.class, Environment.class, GetOpts.class),
                PiplexInit.class.getName(),
                "--from", description.toString(), "--quiet", "--out", into.toString())
                .start();

        final int status = process.waitFor();
        final String errors = new String(process.getErrorStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertEquals(2, status, errors);
        assertFalse(Files.exists(into), "The refused process created its output directory");
        assertTrue(errors.contains("Nothing written."), errors);
    }

    private static String classPath(final Class<?>... roots) throws Exception {
        final Set<String> entries = new LinkedHashSet<>();
        for (final Class<?> root : roots) {
            entries.add(Path.of(root.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toString());
        }
        return String.join(System.getProperty("path.separator"), entries);
    }

    private static Estate invalid() {
        return new Estate(Environment.of("prod"), List.of(), "", Transport.ALLOWALL,
                Estate.SECRETS, Operator.SHARED,
                List.of(Work.of("eod", "eod-switch", "eod-owner", null, null)),
                Grants.PER_ENVIRONMENT);
    }
}
