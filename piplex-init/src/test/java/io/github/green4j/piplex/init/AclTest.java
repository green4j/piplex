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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The generated ACL against three written by hand.
 *
 * <p>These three were written and reviewed by a person, and the integration suite loads what this
 * generates into a real node -- so between them they answer the two questions worth asking about a
 * generator: does a cluster admit what it writes, and is what it writes what somebody meant. This
 * is the second. A generator checked only against its own output is checked against nothing, which
 * is why the reference files stay even though nobody installs them any more.
 */
class AclTest {

    private static final String NODES = "n1=10.0.0.11:7101";
    private static final String ACTIVE = "/dc/active";

    @Test
    void generatesTheSharedTemplateThatShips() throws IOException {
        assertMatches("work-and-operator-shared.conf",
                estate(Grants.PER_ENVIRONMENT, Operator.SHARED));
    }

    @Test
    void generatesTheNarrowedTemplateThatShips() throws IOException {
        assertMatches("work-narrowed.conf", estate(Grants.PER_KEY, Operator.SHARED));
    }

    @Test
    void generatesTheSeparatedTemplateThatShips() throws IOException {
        assertMatches("operator-apart.conf",
                estate(Grants.PER_KEY, new Operator("piplex-ops", "piplex-ops-token")));
    }

    // Compares grant for grant, with the reference files' one hedge taken out. They grant read on the
    // active key AND write on the designation, which no single piece of work can name: the two answer
    // the same question and a request carrying both is refused. Harmless in a file written to be
    // adapted -- a read grant on a key nothing consults -- but it means they describe no one
    // configuration, so the generator cannot reproduce them exactly and should not.
    private static void assertMatches(final String file, final Estate estate) throws IOException {
        assertEquals(List.of(), estate.problems(), "The estate behind a shipped template must be sound");
        final Map<String, List<String>> shipped = grantsIn(shipped(file));
        shipped.values().forEach(grants -> grants.remove(ACTIVE + ":G"));
        assertEquals(shipped, grantsIn(Acl.render(estate)),
                "The generated ACL must be the one a person wrote, grant for grant");
    }

    // Each identity and what it is granted, with comments, continuations and the spacing somebody
    // aligned by hand taken out.
    private static Map<String, List<String>> grantsIn(final String text) {
        final Map<String, List<String>> lines = new LinkedHashMap<>();
        final String joined = text.replace("\\\n", " ");
        for (final String line : joined.split("\n")) {
            final String stripped = line.strip();
            if (!stripped.startsWith("acl.")) {
                continue;
            }
            final int equals = stripped.indexOf('=');
            final String who = stripped.substring("acl.".length(), equals).strip();
            final List<String> grants = new ArrayList<>();
            for (final String grant : stripped.substring(equals + 1).split(";")) {
                if (!grant.isBlank()) {
                    grants.add(grant.strip());
                }
            }
            lines.put(who, grants);
        }
        return lines;
    }

    private static String shipped(final String file) throws IOException {
        try (InputStream in = AclTest.class.getResourceAsStream("/acl/" + file)) {
            assertNotNull(in, "The reference ACL '" + file + "' is missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // The estate the three reference files describe: two controllers, one nightly round.
    private static Estate estate(final Grants grants, final Operator operator) {
        return new Estate(Environment.of("prod"),
                List.of(new Controller("euc1-blue", "piplex-euc1-blue"),
                        new Controller("euc1-green", "piplex-euc1-green")),
                NODES,
                operator.apart() ? Transport.MTLS : Transport.ALLOWALL, Estate.SECRETS,
                operator,
                List.of(Work.of("eod", "eod-switch", "eod-owner", null, "data/euc1")),
                grants);
    }
}
