/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.exclusive;

import io.github.green4j.piplex.Generation;
import io.github.green4j.piplex.ManualTime;
import io.github.green4j.piplex.milestone.Milestones;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.memory.InMemoryCoordinationStore;
import io.github.green4j.piplex.switches.Switch;
import io.github.green4j.piplex.switches.Switches;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExclusiveRunsTest {

    private static final String KEY = "eod";
    private static final String BLUE = "euc1-blue";
    private static final String GREEN = "eus1-blue";
    private static final Generation TODAY = Generation.of("2026-09-12");
    private static final Duration LEASE = Duration.ofSeconds(60);

    private ManualTime time;
    private InMemoryCoordinationStore store;
    private Milestones milestones;
    private ExclusiveRuns runs;

    @BeforeEach
    void setUp() {
        time = new ManualTime();
        store = new InMemoryCoordinationStore(time);
        milestones = new Milestones(store, time);
        runs = new ExclusiveRuns(store, time);
    }

    // ---- elected: whoever takes the lease runs -------------------------------------------------

    @Test
    void admitsExactlyOneOfTwoCandidates() {
        final Admission first = join(runs.begin(elected(BLUE).build()));
        final Admission second = join(runs.begin(elected(GREEN).build()));

        assertInstanceOf(Admitted.class, first);
        assertEquals(BLUE + "/" + BLUE + "#1", assertInstanceOf(Admission.HeldByOther.class, second).heldBy());
    }

    @Test
    void letsTheNextCandidateInOnceTheLeaseIsGivenUp() {
        final Admitted held = assertInstanceOf(Admitted.class, join(runs.begin(elected(BLUE).build())));
        join(held.release());
        assertInstanceOf(Admitted.class, join(runs.begin(elected(GREEN).build())));
    }

    // ---- designated: an externally named owner --------------------------------------------------

    @Test
    void admitsOnlyTheDesignatedOwner() {
        designate(BLUE);
        assertInstanceOf(Admitted.class, join(runs.begin(designated(BLUE).build())));
    }

    @Test
    void turnsAwayAControllerWhichIsNotDesignated() {
        designate(BLUE);
        final Admission refused = join(runs.begin(designated(GREEN).build()));
        assertEquals(BLUE, assertInstanceOf(Admission.NotDesignated.class, refused).currentOwner());
    }

    @Test
    void turnsEverybodyAwayWhenNobodyIsDesignated() {
        final Admission refused = join(runs.begin(designated(BLUE).build()));
        assertNull(assertInstanceOf(Admission.NotDesignated.class, refused).currentOwner());
    }

    @Test
    void stillRefusesASecondConcurrentRunByTheDesignatedOwner() {
        designate(BLUE);
        assertInstanceOf(Admitted.class, join(runs.begin(designated(BLUE, "#1").build())));
        // designation says yes, the lease says not twice at once -- a different run on the same controller
        final Admission second = join(runs.begin(designated(BLUE, "#2").build()));
        assertEquals(BLUE + "/" + BLUE + "#1",
                assertInstanceOf(Admission.HeldByOther.class, second).heldBy());
    }

    // ---- the work is already done ---------------------------------------------------------------

    @Test
    void doesNotRunWorkAlreadyDoneForThisGeneration() {
        designate(BLUE);
        join(milestones.publish("data/euc1", TODAY, BLUE, "eod#1"));

        final Admission done = join(runs.begin(designated(BLUE)
                .generation(TODAY)
                .completedWhen("data/euc1")
                .build()));
        assertEquals(TODAY, assertInstanceOf(Admission.AlreadyCompleted.class, done).reached());
    }

    @Test
    void runsWhenTheMilestoneIsBehindThisGeneration() {
        designate(BLUE);
        join(milestones.publish("data/euc1", Generation.of("2026-09-11"), BLUE, "eod#0"));

        assertInstanceOf(Admitted.class, join(runs.begin(designated(BLUE)
                .generation(TODAY)
                .completedWhen("data/euc1")
                .build())));
    }

    // ---- the switch -----------------------------------------------------------------------------

    @Test
    void doesNotRunWhenSwitchedOff() {
        designate(BLUE);
        put(ExclusiveRuns.switchKey(KEY), new Switch(false, "drain for maintenance").toJson());

        final Admission off = join(runs.begin(designated(BLUE).enabledBy(KEY).build()));
        assertEquals("drain for maintenance",
                assertInstanceOf(Admission.Disabled.class, off).reason());
    }

    @Test
    void treatsAnAbsentSwitchAsOn() {
        designate(BLUE);
        assertInstanceOf(Admitted.class, join(runs.begin(designated(BLUE).enabledBy(KEY).build())));
    }

    // ---- handover while a run is in flight ------------------------------------------------------

    @Test
    void revokesTheHolderWhenTheDesignationChanges() {
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class, join(runs.begin(designated(BLUE).build())));
        final List<Revocation> seen = new ArrayList<>();
        held.onRevoked(seen::add);

        assertTrue(held.isHeld());
        designate(GREEN);

        assertFalse(held.isHeld(), "the holder must stop believing it owns the work");
        assertEquals(1, seen.size());
        assertEquals(Revocation.Reason.DESIGNATION_CHANGED, seen.get(0).reason());
        assertEquals(GREEN, seen.get(0).newOwner());
    }

    @Test
    void handsOverToACandidateParkedWaitingForIt() {
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class, join(runs.begin(designated(BLUE).build())));

        final CompletableFuture<Admission> parked = runs.begin(designated(GREEN)
                .handoverWait(Duration.ofHours(4))
                .build()).toCompletableFuture();
        assertFalse(parked.isDone(), "not designated, so it waits rather than exits");

        designate(GREEN);

        assertFalse(held.isHeld());
        assertTrue(parked.isDone(), "the new owner must pick it up without waiting for a schedule");
        assertInstanceOf(Admitted.class, parked.join());
    }

    @Test
    void parkingForHoursAccumulatesNothing() {
        designate(BLUE);
        assertInstanceOf(Admitted.class, join(runs.begin(designated(BLUE).build())));

        final CompletableFuture<Admission> parked = runs.begin(designated(GREEN)
                .handoverWait(Duration.ofHours(4))
                .build()).toCompletableFuture();

        // A park is a loop of short rounds, and the question is whether a round leaves anything behind
        // it. Three controllers waiting all night is only reasonable if the answer is no: the obvious
        // implementation, where each round starts a watch that lives until the end of the whole window,
        // ends the night with hundreds of them outstanding against the same two keys.
        time.advance(Duration.ofHours(1L));
        final int afterAnHour = time.pending();

        time.advance(Duration.ofHours(1L));
        assertFalse(parked.isDone(), "still nobody else's turn");
        assertEquals(afterAnHour, time.pending(),
                "a second hour of waiting must cost exactly what the first one did");
        assertTrue(afterAnHour <= 8,
                "and that must be a handful of timers, not one per round: " + afterAnHour);
    }

    @Test
    void recognisesItsOwnLeaseWhenAnAcquireIsRetried() {
        designate(BLUE);
        assertInstanceOf(Admitted.class, join(runs.begin(designated(BLUE, "#7").build())));
        // the same run asking again -- what a timed-out acquire looks like on retry
        assertInstanceOf(Admitted.class, join(runs.begin(designated(BLUE, "#7").build())));
    }

    @Test
    void tellsALateListenerThatOwnershipIsAlreadyGone() {
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class, join(runs.begin(designated(BLUE).build())));
        designate(GREEN);

        final List<Revocation> seen = new ArrayList<>();
        held.onRevoked(seen::add);
        assertEquals(1, seen.size(), "registering after the fact must not lose the notification");
    }

    // ---- parking ends for other reasons too -----------------------------------------------------

    @Test
    void stopsParkingWhenTheWorkGetsDoneElsewhere() {
        designate(BLUE);
        final CompletableFuture<Admission> parked = runs.begin(designated(GREEN)
                .generation(TODAY)
                .completedWhen("data/euc1")
                .handoverWait(Duration.ofHours(4))
                .build()).toCompletableFuture();
        assertFalse(parked.isDone());

        join(milestones.publish("data/euc1", TODAY, BLUE, "eod#1"));

        assertTrue(parked.isDone());
        assertInstanceOf(Admission.AlreadyCompleted.class, parked.join());
    }

    @Test
    void givesUpWhenTheHandoverWindowRunsOut() {
        designate(BLUE);
        final CompletableFuture<Admission> parked = runs.begin(designated(GREEN)
                .handoverWait(Duration.ofHours(4))
                .build()).toCompletableFuture();
        assertFalse(parked.isDone());

        time.advance(Duration.ofHours(4));

        assertTrue(parked.isDone());
        assertInstanceOf(Admission.NotDesignated.class, parked.join());
    }

    // ---- the lease underneath -------------------------------------------------------------------

    @Test
    void keepsTheLeaseAliveWhileTheRunLasts() {
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class, join(runs.begin(designated(BLUE).build())));

        time.advance(LEASE.multipliedBy(10));

        assertTrue(held.isHeld(), "renewal must outlive the lease it is renewing");
        assertInstanceOf(Admission.HeldByOther.class, join(runs.begin(designated(BLUE, "#2").build())));
    }

    @Test
    void carriesAFencingTokenWhichIncreasesOnEveryAcquisition() {
        final Admitted first = assertInstanceOf(Admitted.class, join(runs.begin(elected(BLUE).build())));
        join(first.release());
        final Admitted second = assertInstanceOf(Admitted.class, join(runs.begin(elected(GREEN).build())));
        assertTrue(second.fencingToken() > first.fencingToken());
    }

    @Test
    void stopsTheRunWhenItIsSwitchedOffUnderneath() {
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class,
                join(runs.begin(designated(BLUE).enabledBy(KEY).build())));
        final List<Revocation> seen = new ArrayList<>();
        held.onRevoked(seen::add);

        put(ExclusiveRuns.switchKey(KEY), new Switch(false, "incident 4711").toJson());

        assertFalse(held.isHeld());
        assertEquals(Revocation.Reason.DISABLED, seen.get(0).reason());
    }

    @Test
    void toleratesAnUnreachableStoreForTheGracePeriodAndThenGivesUp() {
        final UnreachableStore flaky = new UnreachableStore(store);
        final ExclusiveRuns theirRuns = new ExclusiveRuns(flaky, time);

        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class, join(theirRuns.begin(
                designated(BLUE).renewalGrace(Duration.ofMinutes(5)).build())));
        final List<Revocation> seen = new ArrayList<>();
        held.onRevoked(seen::add);

        flaky.stopAnsweringRenewals();

        time.advance(Duration.ofMinutes(4));
        assertTrue(held.isHeld(), "a blink of the store must not kill a run in progress");

        time.advance(Duration.ofMinutes(2));
        assertFalse(held.isHeld(), "but acting as the owner on no evidence is the worse mistake");
        assertEquals(Revocation.Reason.RENEWAL_FAILED, seen.get(0).reason());
    }

    @Test
    void givesUpTheRunWhenTheLeaseItWasHoldingTurnsOutToBeGone() {
        final UnreachableStore flaky = new UnreachableStore(store);
        final ExclusiveRuns theirRuns = new ExclusiveRuns(flaky, time);

        final Admitted held = assertInstanceOf(Admitted.class, join(theirRuns.begin(
                elected(BLUE).renewalGrace(Duration.ofHours(1)).build())));
        final List<Revocation> seen = new ArrayList<>();
        held.onRevoked(seen::add);

        // Out of touch long enough for the lease to lapse, and nowhere near long enough for the grace
        // period to run out. So the store comes back with an answer no unreachable store ever gives:
        // the renewal is refused rather than failed, and this run is not the owner any more.
        flaky.stopAnsweringRenewals();
        time.advance(LEASE.multipliedBy(2));
        assertTrue(held.isHeld());

        flaky.answerRenewalsAgain();
        time.advance(LEASE);

        assertFalse(held.isHeld(), "a refused renewal is somebody else's lease, not a blink of the store");
        assertEquals(Revocation.Reason.LEASE_LOST, seen.get(0).reason());
    }

    // ---- a guard which stops making sense ------------------------------------------------------

    @Test
    void stopsTheRunWhenTheDesignationItIsWatchingStopsBeingReadable() {
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class, join(runs.begin(designated(BLUE).build())));
        final List<Revocation> seen = new ArrayList<>();
        held.onRevoked(seen::add);

        // Somebody editing the key by hand and getting it wrong, which is the only way this happens.
        overwrite(Designations.keyOf(KEY), "{\"owner\": ");

        // The failure this is against is not the bad JSON: it is a run which looks supervised and is
        // not. Before, the parse blew up inside the watch, the watch was never armed again, and the
        // lease went on being renewed over work nothing was left watching.
        assertFalse(held.isHeld(), "a run whose designation cannot be read must not keep running");
        assertEquals(1, seen.size());
        assertEquals(Revocation.Reason.GUARD_UNREADABLE, seen.get(0).reason());
        assertTrue(seen.get(0).detail().contains(Designations.keyOf(KEY)),
                "the revocation has to name the key somebody must go and fix, was: " + seen.get(0));
    }

    @Test
    void stopsTheRunWhenTheSwitchItIsWatchingStopsBeingReadable() {
        designate(BLUE);
        final Admitted held = assertInstanceOf(
                Admitted.class, join(runs.begin(designated(BLUE).enabledBy(KEY).build())));
        final List<Revocation> seen = new ArrayList<>();
        held.onRevoked(seen::add);

        put(Switches.keyOf(KEY), "not json at all");

        assertFalse(held.isHeld(), "a run whose switch cannot be read must not keep running");
        assertEquals(Revocation.Reason.GUARD_UNREADABLE, seen.get(0).reason());
        assertTrue(seen.get(0).detail().contains(Switches.keyOf(KEY)),
                "the revocation has to name the key somebody must go and fix, was: " + seen.get(0));
    }

    @Test
    void picksTheDesignationBackUpAfterLosingSightOfIt() {
        final UnreachableStore flaky = new UnreachableStore(store);
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class,
                join(new ExclusiveRuns(flaky, time).begin(designated(BLUE).build())));
        final List<Revocation> seen = new ArrayList<>();
        held.onRevoked(seen::add);

        flaky.stopAnsweringWatches();
        time.advance(LEASE.multipliedBy(5));
        assertTrue(held.isHeld(), "a watch which cannot reach its key says nothing about who owns this");

        flaky.answerWatchesAgain();
        time.advance(LEASE);
        designate(GREEN);

        // The watch has to have re-armed itself: nothing else in a run in flight ever looks at this key.
        assertFalse(held.isHeld(), "a handover after the blink must still be noticed");
        assertEquals(Revocation.Reason.DESIGNATION_CHANGED, seen.get(0).reason());
    }

    @Test
    void picksTheSwitchBackUpAfterLosingSightOfIt() {
        final UnreachableStore flaky = new UnreachableStore(store);
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class,
                join(new ExclusiveRuns(flaky, time).begin(designated(BLUE).enabledBy(KEY).build())));
        final List<Revocation> seen = new ArrayList<>();
        held.onRevoked(seen::add);

        flaky.stopAnsweringWatches();
        time.advance(LEASE.multipliedBy(5));
        assertTrue(held.isHeld());

        flaky.answerWatchesAgain();
        time.advance(LEASE);
        put(ExclusiveRuns.switchKey(KEY), new Switch(false, "incident 4711").toJson());

        assertFalse(held.isHeld(), "being switched off after the blink must still stop the run");
        assertEquals(Revocation.Reason.DISABLED, seen.get(0).reason());
    }

    // ---- helpers --------------------------------------------------------------------------------

    private ExclusiveRequest.Builder elected(final String owner) {
        return elected(owner, "#1");
    }

    private ExclusiveRequest.Builder elected(final String owner, final String run) {
        return ExclusiveRequest.builder(KEY).ownedBy(owner).runId(owner + run).lease(LEASE);
    }

    private ExclusiveRequest.Builder designated(final String owner) {
        return elected(owner).designatedBy(KEY);
    }

    private ExclusiveRequest.Builder designated(final String owner, final String run) {
        return elected(owner, run).designatedBy(KEY);
    }

    private void designate(final String owner) {
        join(new Designations(store, time).designate(KEY, owner, "test", "handover"));
    }

    private void put(final String key, final String value) {
        assertTrue(join(store.compareAndSet(key, CoordinationStore.INITIAL_VERSION, value)));
    }

    private void overwrite(final String key, final String value) {
        assertTrue(join(store.compareAndSet(key, join(store.get(key)).version(), value)));
    }

    private static <T> T join(final CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
