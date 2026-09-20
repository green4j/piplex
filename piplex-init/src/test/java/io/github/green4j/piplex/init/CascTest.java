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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a generated controller configuration must and must not contain. */
class CascTest {

    private static final Controller BLUE = new Controller("euc1-blue", "piplex-euc1-blue");
    private static final Controller GREEN = new Controller("euc1-green", "piplex-euc1-green");

    // The whole reason the paths are fields and the pass phrases are variables. This file is
    // generated, and a generated file is committed: a pass phrase in it is a pass phrase published.
    @ParameterizedTest
    @EnumSource(Transport.class)
    void namesEverySecretAndWritesNone(final Transport transport) {
        final String yaml = Casc.render(estate(transport), BLUE);

        assertFalse(yaml.contains("changeit"), yaml);
        assertFalse(yaml.contains("a-shared-secret"), yaml);
        for (final String line : yaml.split("\n")) {
            final boolean secret = line.contains("token:") || line.contains("Password:");
            if (secret) {
                assertTrue(line.contains("${"), "A secret must be a variable, not a value: " + line);
            }
        }
    }

    // Two controllers that agree on these two are one controller as far as the cluster is concerned,
    // and which of them runs the work is chance.
    @Test
    void tellsTheControllersApart() {
        assertTrue(Casc.render(estate(Transport.MTLS), BLUE)
                .contains("ownerId: \"euc1-blue\""));
        assertTrue(Casc.render(estate(Transport.MTLS), GREEN)
                .contains("clientId: \"piplex-euc1-green\""));
        assertTrue(Casc.render(estate(Transport.MTLS), GREEN)
                .contains("tlsKeystore: \"/run/secrets/piplex-euc1-green.p12\""),
                "Each controller presents its own certificate, whose CN is its own client id");
    }

    @Test
    void namesTheEnvironmentRatherThanLeavingItToDefault() {
        assertTrue(Casc.render(estate(Transport.ALLOWALL), BLUE).contains("environment: \"prod\""),
                "A controller whose default environment is wrong writes another estate's keys");
    }

    @Test
    void asksForNoTrustWhereTheClusterOffersNone() {
        final String yaml = Casc.render(estate(Transport.ALLOWALL), BLUE);

        assertFalse(yaml.contains("tls:"), yaml);
        assertFalse(yaml.contains("token:"), yaml);
        assertTrue(yaml.contains("nothing here proves who this"),
                "And it says so, because this is a choice somebody made rather than a default");
    }

    private static Estate estate(final Transport transport) {
        return new Estate(Environment.of("prod"), List.of(BLUE, GREEN), "n1=10.0.0.11:7101",
                transport, Estate.SECRETS, Operator.SHARED,
                List.of(Work.of("eod", "eod-switch", "eod-owner", null, "data/euc1")),
                Grants.PER_KEY);
    }
}
