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
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What filling a job in may and may not change.
 *
 * <p>Whether the result runs is asked where it can be answered -- against a real Jenkins, in
 * {@code OperatorJobsTest}, which builds its jobs from this. What is asked here is the part that
 * has no Jenkins in it: that the estate lands in the block, that nothing else moves, and that a name
 * from a file somebody typed cannot become code.
 */
class JobsTest {

    private static final Controller BLUE = new Controller("euc1-blue", "piplex-euc1-blue");
    private static final Controller GREEN = new Controller("euc1-green", "piplex-euc1-green");

    static List<String> jobs() {
        return Jobs.ALL;
    }

    @ParameterizedTest
    @MethodSource("jobs")
    void leavesTheProcedureExactlyAsItShips(final String job) {
        final String filled = Jobs.render(estate(Operator.SHARED), BLUE, job);
        final String template = Jobs.template(job);

        assertEquals(below(template), below(filled),
                "Everything under the generated block is the procedure, and it is not generated");
        assertEquals(above(template), above(filled),
                "Everything above the generated block explains the job, and it is not generated");
    }

    @ParameterizedTest
    @MethodSource("jobs")
    void fillsInTheEstateEveryJobIsInstalledWith(final String job) {
        final String filled = Jobs.render(estate(Operator.SHARED), GREEN, job);

        assertTrue(filled.contains("final String ENVIRONMENT = 'prod'"), filled);
        assertTrue(filled.contains("final String OWNER_ID = 'euc1-green'"), filled);
        assertTrue(filled.contains("final List OWNERS = ['euc1-blue', 'euc1-green']"), filled);
        assertTrue(filled.contains("'eod': [key: 'eod', enabledBy: 'nightly'"), filled);
        assertFalse(filled.contains("final Map WORK = [:]"), "The empty estate must be gone");
    }

    @ParameterizedTest
    @MethodSource("jobs")
    void givesTheOperationsCredentialToEveryJobThatWritesUnderIt(final String job) {
        final String filled = Jobs.render(
                estate(new Operator("piplex-ops", "piplex-ops-token")), BLUE, job);

        // Draining is the exception, and the reason is the one the shipped ACL states: a controller
        // takes itself out under its own identity, or the operations credential ends up on every
        // controller and separating it bought nothing.
        final boolean drain = job.equals("drain-controller.groovy");
        assertEquals(!drain, filled.contains("final String CLIENT_ID = 'piplex-ops'"), filled);
        assertEquals(!drain, filled.contains("final String CREDENTIALS_ID = 'piplex-ops-token'"),
                filled);
    }

    // A work key comes from a properties file somebody typed and lands in a Groovy literal that is
    // executed on a controller. Nothing else in the generated block comes from outside.
    @Test
    void cannotBeTalkedIntoWritingCodeInsteadOfAName() {
        final Estate estate = new Estate(Environment.of("prod"), List.of(BLUE), "n1=10.0.0.1:7101",
                Transport.ALLOWALL, Estate.SECRETS, Operator.SHARED,
                List.of(Work.of("eod', x: ''.execute(), y: '", "nightly", "eod-owner", null, null)),
                Grants.PER_ENVIRONMENT);

        final String filled = Jobs.render(estate, BLUE, "inspect.groovy");

        assertTrue(filled.contains("'eod\\', x: \\'\\'.execute(), y: \\''"), filled);
        assertFalse(filled.contains("''.execute()"), "A quote somebody typed must not close the literal");
    }

    private static String above(final String job) {
        return job.substring(0, job.indexOf("// ---- piplex-init"));
    }

    private static String below(final String job) {
        return job.substring(job.indexOf("// ---- end piplex-init ----"));
    }

    private static Estate estate(final Operator operator) {
        return new Estate(Environment.of("prod"), List.of(BLUE, GREEN),
                "n1=10.0.0.11:7101,n2=10.0.0.12:7101,n3=10.0.0.13:7101",
                operator.apart() ? Transport.MTLS : Transport.ALLOWALL, Estate.SECRETS, operator,
                List.of(Work.of("eod", "nightly", "eod-owner", null, "data/euc1"),
                        Work.of("corrections", "nightly", "eod-owner", null, "data/corrections")),
                Grants.PER_KEY);
    }
}
