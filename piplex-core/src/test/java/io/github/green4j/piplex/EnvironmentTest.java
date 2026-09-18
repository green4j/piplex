/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex;

import io.github.green4j.piplex.exclusive.Admission;
import io.github.green4j.piplex.exclusive.Admitted;
import io.github.green4j.piplex.exclusive.ExclusiveRequest;
import io.github.green4j.piplex.observe.PiplexObserver;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.memory.InMemoryCoordinationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentTest {

    private static final String KEY = "eod";
    private static final String OWNER = "euc1-blue";
    private static final Generation TODAY = Generation.of("2026-09-12");
    private static final Duration LEASE = Duration.ofSeconds(60);

    private final ManualTime time = new ManualTime();

    private CoordinationStore store;
    private Piplex prod;
    private Piplex uat;

    @BeforeEach
    void setUp() {
        store = new InMemoryCoordinationStore(time);
        prod = new Piplex(store, time, PiplexObserver.NONE, Environment.of("prod"));
        uat = prod.in(Environment.of("uat"));
    }

    @Test
    void namesItsOwnSegmentOfEveryKeyPiplexWrites() {
        assertEquals("piplex/prod/milestone/", Environment.of("prod").prefixOf("milestone"));
        assertEquals("piplex/default/enabled/", Environment.DEFAULT.prefixOf("enabled"));
        assertTrue(Environment.of("prod").prefixOf("exclusive").startsWith(Environment.ROOT),
                "Every key piplex writes has to stay under the one root it owns");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void refusesAnEnvironmentWhichNamesNothing(final String blank) {
        assertThrows(IllegalArgumentException.class, () -> Environment.of(blank));
    }

    @Test
    void refusesAnEnvironmentWhichWouldReadAsTwoSegments() {
        // Not cosmetic: 'a/b' puts its milestones at piplex/a/b/milestone/eod, which is where an
        // environment named 'a' would keep a kind of record called 'b'. Two environments, one key.
        assertThrows(IllegalArgumentException.class, () -> Environment.of("team/blue"));
        assertThrows(NullPointerException.class, () -> Environment.of(null));
    }

    @Test
    void fallsBackToTheDefaultForAHostWhichNamesNone() {
        assertEquals(Environment.DEFAULT, Environment.orDefault(null));
        assertEquals(Environment.DEFAULT, Environment.orDefault(" "));
        assertEquals(Environment.of("uat"), Environment.orDefault("uat"));
        assertEquals("default", Environment.DEFAULT.value());
    }

    @Test
    void handsBackItselfWhenAskedForTheEnvironmentItIsAlreadyIn() {
        assertSame(prod, prod.in(Environment.of("prod")));
        assertEquals(Environment.of("uat"), uat.environment());
        assertNotEquals(prod.environment(), uat.environment());
    }

    // ---- what the segment is for ----------------------------------------------------------------

    @Test
    void admitsTheSameWorkInTwoEnvironmentsAtOnce() {
        // The requirement itself. One cluster serving production and uat must run the nightly job in
        // both tonight; a lease shared between them would let only one of the two go.
        final Admission inProd = join(prod.runs().begin(request()));
        final Admission inUat = join(uat.runs().begin(request()));

        assertInstanceOf(Admitted.class, inProd);
        assertInstanceOf(Admitted.class, inUat);
    }

    @Test
    void keepsTheSecondCandidateOutWithinOneEnvironment() {
        // The other half of it: the segment must not weaken the lease inside an environment.
        assertInstanceOf(Admitted.class, join(prod.runs().begin(request())));

        final Admission second = join(prod.runs().begin(
                ExclusiveRequest.builder(KEY).ownedBy("eus1-blue").runId("eus1-blue#1").lease(LEASE).build()));

        assertInstanceOf(Admission.HeldByOther.class, second);
    }

    @Test
    void doesNotLetAMilestoneInOneEnvironmentAnswerForAnother() {
        join(prod.milestones().publish(KEY, TODAY, OWNER, "eod#1"));

        assertEquals(TODAY, join(prod.milestones().current(KEY)).generation());
        assertNull(join(uat.milestones().current(KEY)), "A milestone must not be published for uat too");
    }

    @Test
    void doesNotLetASwitchInOneEnvironmentDrainAnother() {
        join(prod.switches().disable(KEY, "INC-4821"));

        assertFalse(join(prod.switches().current(KEY)).enabled());
        assertTrue(join(uat.switches().current(KEY)).enabled(), "An absent switch means enabled");
    }

    @Test
    void doesNotLetADesignationInOneEnvironmentSayWhoRunsInAnother() {
        join(prod.designations().designate(KEY, OWNER, "INC-4821"));

        assertEquals(OWNER, join(prod.designations().current(KEY)).owner());
        assertNull(join(uat.designations().current(KEY)), "Nobody is designated in uat");
    }

    private ExclusiveRequest request() {
        return ExclusiveRequest.builder(KEY).ownedBy(OWNER).runId(OWNER + "#1").lease(LEASE).build();
    }

    private static <T> T join(final CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
