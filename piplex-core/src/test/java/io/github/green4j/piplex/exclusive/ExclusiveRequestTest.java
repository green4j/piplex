/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.exclusive;

import io.github.green4j.piplex.Generation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExclusiveRequestTest {

    private static final String KEY = "eod";
    private static final String OWNER = "euc1-blue";
    private static final String RUN = "eod#142";

    @Test
    void fillsRenewalAndGraceInFromTheLease() {
        final ExclusiveRequest request = builder().lease(Duration.ofSeconds(90)).build();

        assertEquals(Duration.ofSeconds(30), request.renewEvery());
        assertEquals(Duration.ofSeconds(90), request.renewalGrace());
        assertEquals(Duration.ofSeconds(90), request.guardGrace(), "A key follows the store it lives in");
    }

    @Test
    void refusesAWorkSwitchThatCouldBeAnOwnersSwitch() {
        // eod/@blue is how blue's own switch for eod is kept.
        assertThrows(IllegalArgumentException.class, () -> builder().enabledBy("eod/@blue").build());
        assertThrows(IllegalArgumentException.class, () -> builder().enabledBy("@eod").build());
        builder().enabledBy("eod/blue").build();
    }

    static List<Typed> durationsItRefuses() {
        final Duration negative = Duration.ofSeconds(-1);
        return List.of(
                // A negative one puts every deadline the holder keeps in the past, so the run is admitted
                // and revoked in the same breath, which reads as a coordination fault rather than a typo.
                new Typed("renewalGrace", b -> b.renewalGrace(negative)),
                new Typed("guardGrace", b -> b.guardGrace(negative)),
                new Typed("handoverWait", b -> b.handoverWait(negative)),
                // Zero puts the deadline at the admission itself, so the run would be revoked as it starts.
                new Typed("renewalGrace", b -> b.renewalGrace(Duration.ZERO)),
                new Typed("guardGrace", b -> b.guardGrace(Duration.ZERO)),
                new Typed("lease", b -> b.lease(Duration.ZERO)),
                new Typed("renewEvery", b -> b.renewEvery(Duration.ofMinutes(5))));
    }

    @Test
    void refusesAnActiveKeyItCouldNotConsult() {
        assertThrows(IllegalArgumentException.class, () -> builder().activeWhen("/dc/active", null).build());
        assertThrows(IllegalArgumentException.class, () -> builder().activeWhen(null, "euc1-blue").build());
        assertThrows(IllegalArgumentException.class, () -> builder().activeWhen(" ", "euc1-blue").build());
        assertThrows(IllegalArgumentException.class, () -> builder().activeWhen("/dc/active", " ").build());
        // Piplex's own records: a lease or a designation read as a site name.
        assertThrows(IllegalArgumentException.class,
                () -> builder().activeWhen("piplex/exclusive/eod", "euc1-blue").build());
        builder().activeWhen("/dc/active", "euc1-blue").build();
    }

    @Test
    void refusesTwoAnswersToWhoRuns() {
        // Designated blue, active green: nobody would run, and nothing would say why.
        assertThrows(IllegalArgumentException.class, () -> builder().designatedBy("eod-owner")
                .activeWhen("/dc/active", "euc1-blue").build());
        builder().enabledBy("eod").completedWhen("eod").generation(Generation.of("2026-09-12"))
                .activeWhen("/dc/active", "euc1-blue").build();
    }

    @ParameterizedTest
    @MethodSource("durationsItRefuses")
    void refusesADurationItCouldNotWorkWith(final Typed typed) {
        final IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> typed.setting().apply(builder()).build());
        assertTrue(refused.getMessage().startsWith(typed.named()), refused.getMessage());
    }

    @Test
    void refusesAKeyWhichWouldNameTheWholePrefix() {
        // Every key an attempt turns on is a prefix plus this one, so a blank key competes for the
        // prefix itself: one lease shared by every piece of work on the controller, which reads as
        // contention nobody can account for.
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> ExclusiveRequest.builder("  ").ownedBy(OWNER).runId(RUN).build());
        assertTrue(refused.getMessage().startsWith("key"), refused.getMessage());
    }

    @ParameterizedTest
    @CsvSource({
        "ownerId",
        "runId",
        "executionId",
        "designatedBy",
        "enabledBy",
        "completedWhen",
    })
    void refusesEveryOtherNameItWouldTurnIntoAKey(final String field) {
        // The key builders refuse these too, but mid-admission and naming neither the request nor the
        // field. A blank ownerId is not caught even there: it composes the identity "/<runId>".
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> blank(field).build());
        assertTrue(refused.getMessage().startsWith(field), refused.getMessage());
    }

    private static ExclusiveRequest.Builder blank(final String field) {
        return switch (field) {
            case "ownerId" -> ExclusiveRequest.builder(KEY).ownedBy(" ").runId(RUN);
            case "runId" -> ExclusiveRequest.builder(KEY).ownedBy(OWNER).runId(" ");
            case "executionId" -> builder().executionId(" ");
            case "designatedBy" -> builder().designatedBy(" ");
            case "enabledBy" -> builder().enabledBy(" ");
            case "completedWhen" -> builder().completedWhen(" ").generation(Generation.of("2026-09-14"));
            default -> throw new IllegalArgumentException(field);
        };
    }

    // A runId is a build's externalizable id, and a build in a folder carries the folder in it -- so the
    // delimiter is a character the parts may contain, and so is the escape character. Joined without
    // escaping both, two holders would spell their identity one way and share one lease.
    @ParameterizedTest
    @CsvSource({
        "b/c,  , b,   c",
        "b\\/c, , b\\, c",
    })
    void tellsTwoIdentitiesApartWhenAPartOfOneEndsWhereTheNextBegins(final String run,
                                                                   final String execution,
                                                                   final String otherRun,
                                                                   final String otherExecution) {
        assertNotEquals(
                ExclusiveRuns.leaseOwner(ExclusiveRequest.builder(KEY).ownedBy("a").runId(run)
                        .executionId(execution).build()),
                ExclusiveRuns.leaseOwner(ExclusiveRequest.builder(KEY).ownedBy("a").runId(otherRun)
                        .executionId(otherExecution).build()));
    }

    @Test
    void leavesAnOrdinaryIdentityAlone() {
        // What nearly every run is, and what the upgrade note in docs/08-lifecycle.md turns on: an id
        // with neither a slash nor a backslash in it spells its lease exactly as it always did.
        assertEquals(OWNER + "/" + RUN + "/11", ExclusiveRuns.leaseOwner(
                builder().executionId("11").build()));
    }

    private static ExclusiveRequest.Builder builder() {
        return ExclusiveRequest.builder(KEY).ownedBy(OWNER).runId(RUN);
    }


    record Typed(String named, UnaryOperator<ExclusiveRequest.Builder> setting) {
    }
}
