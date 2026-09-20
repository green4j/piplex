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
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The estate written down and read back.
 *
 * <p>This file is the one thing here anybody edits by hand, and the one thing the second run starts
 * from. An answer that does not survive the round trip is an answer somebody gives again.
 */
class EstatesTest {

    @Test
    void readsBackEverythingItWrote(@TempDir final Path directory) throws IOException {
        final Estate estate = new Estate(Environment.of("prod"),
                List.of(new Controller("euc1-blue", "piplex-euc1-blue"),
                        new Controller("euc1-green", "piplex-euc1-green")),
                "n1=10.0.0.11:7101,n2=[2001:db8::1]:7101",
                Transport.MTLS, "/etc/piplex/keys",
                new Operator("piplex-ops", "piplex-ops-cert"),
                List.of(new Work("eod", "nightly", "eod-owner", null, "data/euc1", "90s", "4h"),
                        Work.of("ref-data", "ref-switch", null, "/dc/active", null)),
                Grants.PER_KEY);

        final Path file = directory.resolve(Estates.FILE);
        Files.writeString(file, Estates.render(estate), StandardCharsets.UTF_8);

        assertEquals(estate, Estates.read(file));
    }

    // A node list is full of '=' and a key of '/'. Both go through Properties, which is
    // worth one test rather than one incident.
    @Test
    void keepsANodeListIntactThroughAFileFormatBuiltOnEqualsSigns() throws IOException {
        final String nodes = "n1=10.0.0.11:7101,n2=10.0.0.12:7101,n3=[2001:db8::3]:7101";
        final Properties properties = new Properties();
        properties.load(new StringReader("nodes = " + nodes + "\nwork = data/euc1\n"));

        final Estate estate = Estates.of(properties);

        assertEquals(nodes, estate.nodes());
        assertEquals("data/euc1", estate.work().get(0).key());
    }

    @Test
    void namesWhatItDidNotUnderstandRatherThanChoosingForYou() {
        final Properties properties = new Properties();
        properties.setProperty("transport", "ssl");

        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Estates.of(properties));

        assertTrue(refused.getMessage().contains("'ssl' is not one of"), refused.getMessage());
        assertTrue(refused.getMessage().contains("allowall"), refused.getMessage());
    }

    // What somebody types is not what an enum is called, and nobody should have to know that.
    @Test
    void readsTheWordsSomebodyWouldActuallyType() {
        final Properties properties = new Properties();
        properties.setProperty("transport", "MTLS");
        properties.setProperty("grants", "per-key");

        final Estate estate = Estates.of(properties);

        assertEquals(Transport.MTLS, estate.transport());
        assertEquals(Grants.PER_KEY, estate.grants());
    }

    @Test
    void givesAControllerAClientIdWhereNobodyNamedOne() {
        final Properties properties = new Properties();
        properties.setProperty("controllers", "euc1-blue, euc1-green");
        properties.setProperty("controller.euc1-green.clientId", "ops-green");

        final Estate estate = Estates.of(properties);

        assertEquals("piplex-euc1-blue", estate.controllers().get(0).clientId());
        assertEquals("ops-green", estate.controllers().get(1).clientId());
    }
}
