/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.exclusive;

import io.github.green4j.piplex.Generation;
import io.github.green4j.piplex.ManualTime;
import io.github.green4j.piplex.SilentStore;
import io.github.green4j.piplex.milestone.Milestones;
import io.github.green4j.piplex.milestone.PublishResult;
import io.github.green4j.piplex.observe.PiplexObserver;
import io.github.green4j.piplex.observe.RunRef;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.Entry;
import io.github.green4j.piplex.store.LeaseAttempt;
import io.github.green4j.piplex.store.Watch;
import io.github.green4j.piplex.store.memory.InMemoryCoordinationStore;
import io.github.green4j.piplex.switches.Switch;
import io.github.green4j.piplex.switches.Switches;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExclusiveRunsTest {

    private static final String KEY = "eod";
    private static final String BLUE = "euc1-blue";
    private static final String GREEN = "eus1-blue";
    private static final Generation TODAY = Generation.of("2026-09-12");
    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final String ACTIVE = "/dc/active";

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

    // ---- designated: an externally named owner --------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"somebody else", "nobody", "removed"})
    void turnsAwayACandidateWhichIsNotDesignated(final String designated) {
        final String owner = switch (designated) {
            case "somebody else" -> {
                designate(BLUE);
                yield BLUE;
            }
            case "removed" -> {
                designate(GREEN);
                remove(Designations.keyOf(KEY));
                yield null;
            }
            default -> null;
        };

        final Admission refused = join(runs.begin(designated(GREEN).build()));

        assertEquals(owner, assertInstanceOf(Admission.NotDesignated.class, refused).currentOwner());
    }

    @Test
    void asksAgainRatherThanAdmitOnALeaseTheGuardReadsOutlasted() {
        final List<String> told = new ArrayList<>();
        final CompletableFuture<Admission> asked = new ExclusiveRuns(readsTakeALease(), time, new PiplexObserver() {
            @Override
            public void admitted(final RunRef run, final long fencingToken) {
                told.add("admitted");
            }
        }).begin(completing(BLUE).handoverWait(LEASE.multipliedBy(5)).build()).toCompletableFuture();

        assertFalse(asked.isDone());
        assertEquals(List.of(), told, "A lease already out must not be announced as held");

        time.advance(LEASE);

        assertInstanceOf(Admitted.class, join(asked), "The next round reads in time");
        assertEquals(List.of("admitted"), told);
    }

    // ---- active: an external key names the site allowed to run ---------------------------------

    @Test
    void admitsARunWhileTheActiveKeyHoldsItsValue() {
        put(ACTIVE, BLUE);

        assertInstanceOf(Admitted.class, join(runs.begin(active(BLUE).build())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"another value", "nothing", "removed"})
    void turnsAwayARunWhileTheActiveKeyHoldsSomethingElse(final String held) {
        final String value = switch (held) {
            case "another value" -> {
                put(ACTIVE, GREEN);
                yield GREEN;
            }
            case "removed" -> {
                put(ACTIVE, BLUE);
                remove(ACTIVE);
                yield null;
            }
            default -> null;
        };
        final List<String> told = new ArrayList<>();

        final Admission refused = join(new ExclusiveRuns(store, time, new PiplexObserver() {
            @Override
            public void notActive(final RunRef run, final String key, final String currentValue) {
                told.add(key + "=" + currentValue);
            }
        }).begin(active(BLUE).build()));

        final Admission.NotActive notActive = assertInstanceOf(Admission.NotActive.class, refused);
        assertEquals(ACTIVE, notActive.key());
        assertEquals(value, notActive.currentValue());
        assertEquals(List.of(ACTIVE + "=" + value), told);
    }

    @Test
    void admitsAParkedRunOnceTheActiveKeyTakesItsValue() {
        final CompletableFuture<Admission> parked = runs.begin(active(BLUE)
                .handoverWait(Duration.ofHours(1))
                .build()).toCompletableFuture();
        assertFalse(parked.isDone(), "Nothing is active yet, so it waits");

        put(ACTIVE, GREEN);
        assertFalse(parked.isDone(), "Another site is active");

        overwrite(ACTIVE, BLUE);

        assertInstanceOf(Admitted.class, join(parked));
    }

    @Test
    void doesNotAdmitARunWhoseActiveKeyMovedWhileItWasTakingTheLease() {
        put(ACTIVE, BLUE);
        final ExclusiveRuns racing =
                new ExclusiveRuns(new RacedAcquireStore(store, () -> overwrite(ACTIVE, GREEN)), time);

        final Admission refused = join(racing.begin(active(BLUE).build()));

        assertEquals(GREEN, assertInstanceOf(Admission.NotActive.class, refused).currentValue());
        assertInstanceOf(Admitted.class, join(runs.begin(active(GREEN).build())),
                "The lease taken a moment before must be given back");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void revokesTheHolderAndHandsOverWhenTheActiveKeyMoves(final boolean removed) {
        put(ACTIVE, BLUE);
        final Admitted held = assertInstanceOf(Admitted.class, join(runs.begin(active(BLUE).build())));
        final List<Revocation> seen = revocations(held);
        final CompletableFuture<Admission> parked = runs.begin(active(GREEN)
                .handoverWait(Duration.ofHours(4))
                .build()).toCompletableFuture();

        if (removed) {
            remove(ACTIVE);
        } else {
            overwrite(ACTIVE, GREEN);
        }

        assertFalse(held.isHeld(), "The holder must stop once its site is no longer active");
        assertEquals(1, seen.size());
        assertEquals(Revocation.Reason.DEACTIVATED, seen.get(0).reason());
        assertEquals(removed ? "'" + ACTIVE + "' was removed" : "'" + ACTIVE + "' is now '" + GREEN + "'",
                seen.get(0).detail());
        if (removed) {
            parked.cancel(false);
            return;
        }
        assertFalse(parked.isDone(), "Not while the revoked holder may still be running");

        join(held.release());
        time.advance(LEASE);

        assertInstanceOf(Admitted.class, join(parked));
    }

    @ParameterizedTest
    @MethodSource("activeOutages")
    void givesTheRunUpWhenNothingCanBeLearntAboutTheActiveKeyForTooLong(final Outage outage) {
        final UnreachableStore flaky = new UnreachableStore(store);
        put(ACTIVE, BLUE);
        final Admitted held = assertInstanceOf(Admitted.class,
                join(new ExclusiveRuns(flaky, time).begin(active(BLUE)
                        .guardGrace(Duration.ofSeconds(30))
                        .build())));
        final List<Revocation> seen = revocations(held);

        outage.breaks().accept(flaky);
        time.advance(Duration.ofSeconds(15));
        time.advance(Duration.ofSeconds(25));
        assertTrue(held.isHeld(), "A blink must not kill a run whose lease is being renewed");

        time.advance(Duration.ofSeconds(10));

        assertFalse(held.isHeld());
        assertEquals(Revocation.Reason.GUARD_UNREACHABLE, seen.get(0).reason());
        assertTrue(seen.get(0).detail().contains(ACTIVE), "Was: " + seen.get(0));
    }

    // ---- the work is already done ---------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"2026-09-11, false", "2026-09-12, true", "2026-09-13, true"})
    void doesNotRunWorkAlreadyDoneForThisGeneration(final String reached, final boolean done) {
        designate(BLUE);
        join(milestones.publish("data/euc1", Generation.of(reached), BLUE, "eod#1"));

        final Admission admission = join(runs.begin(completing(BLUE).designatedBy(KEY).build()));

        if (done) {
            assertEquals(Generation.of(reached),
                    assertInstanceOf(Admission.AlreadyCompleted.class, admission).reached());
        } else {
            assertInstanceOf(Admitted.class, admission);
        }
    }

    @Test
    void doesNotRunWorkFinishedWhileItWasTakingTheLease() {
        designate(BLUE);
        // The milestone is read before the lease, and until the lease is held that reading is a guess:
        // the holder can finish the day and let go in between, and this candidate would do it again.
        final ExclusiveRuns racing = new ExclusiveRuns(
                new RacedAcquireStore(store,
                        () -> join(milestones.publish("data/euc1", TODAY, GREEN, "eod#9"))),
                time);

        final Admission done = join(racing.begin(designated(BLUE)
                .generation(TODAY)
                .completedWhen("data/euc1")
                .build()));

        assertEquals(TODAY, assertInstanceOf(Admission.AlreadyCompleted.class, done).reached());
        // And the lease taken a moment before is given back, rather than left for the next run to
        // wait out.
        assertInstanceOf(Admitted.class, join(runs.begin(elected(GREEN).build())));
    }

    @Test
    void doesNotAdmitARunWhoseDesignationMovedWhileItWasTakingTheLease() {
        designate(BLUE);
        final ExclusiveRuns racing = new ExclusiveRuns(new RacedAcquireStore(store, () -> designate(GREEN)), time);

        final Admission refused = join(racing.begin(designated(BLUE).build()));

        // Admitted and revoked a watch later is work started under somebody else's ownership.
        assertEquals(GREEN, assertInstanceOf(Admission.NotDesignated.class, refused).currentOwner());
        assertInstanceOf(Admitted.class, join(runs.begin(designated(GREEN).build())),
                "The lease taken a moment before must be given back");
    }

    @Test
    void doesNotAdmitARunSwitchedOffWhileItWasTakingTheLease() {
        final ExclusiveRuns racing = new ExclusiveRuns(new RacedAcquireStore(store,
                () -> join(new Switches(store, time).disable(KEY, "INC-4821"))), time);

        final Admission refused = join(racing.begin(elected(BLUE).enabledBy(KEY).build()));

        assertEquals("INC-4821", assertInstanceOf(Admission.Disabled.class, refused).reason());
        assertInstanceOf(Admitted.class, join(runs.begin(elected(GREEN).build())),
                "The lease taken a moment before must be given back");
    }

    @Test
    void parksOnADesignationThatMovedWhileItWasTakingTheLease() {
        designate(BLUE);
        final ExclusiveRuns racing = new ExclusiveRuns(new RacedAcquireStore(store, () -> designate(GREEN)), time);

        final CompletableFuture<Admission> parked = racing.begin(designated(BLUE)
                .handoverWait(Duration.ofHours(1))
                .build()).toCompletableFuture();
        assertFalse(parked.isDone(), "Somebody else is designated now, which is what parking waits out");

        parked.cancel(false);
        time.advance(LEASE);
    }

    @Test
    void givesTheLeaseBackWhenTheMilestoneCannotBeReadOnceTheLeaseIsHeld() {
        designate(BLUE);
        final UnreachableStore flaky = new UnreachableStore(store);
        // Readable while the guards are read and not a moment longer, so what fails is the one read
        // made with the lease already in hand.
        final ExclusiveRuns racing = new ExclusiveRuns(
                new RacedAcquireStore(flaky,
                        () -> flaky.stopAnsweringReadsOf(Milestones.keyOf("data/euc1"))),
                time);

        final CompletionException failed = assertThrows(CompletionException.class,
                () -> join(racing.begin(designated(BLUE)
                        .generation(TODAY)
                        .completedWhen("data/euc1")
                        .build())));

        assertInstanceOf(IOException.class, failed.getCause());
        // The caller got the throw instead of the handle, so nothing is left holding the lease and
        // nothing would ever give it back: a whole lease in which nobody is admitted, for one read
        // that did not land.
        assertInstanceOf(Admitted.class, join(runs.begin(elected(GREEN).build())));
    }

    @Test
    void reportsTheFailedReadWhenGivingTheLeaseBackFailsToo() {
        designate(BLUE);
        final UnreachableStore flaky = new UnreachableStore(store);
        final ExclusiveRuns racing = new ExclusiveRuns(
                new RacedAcquireStore(flaky, () -> {
                    flaky.stopAnsweringReadsOf(Milestones.keyOf("data/euc1"));
                    flaky.stopAnsweringReleases();
                }),
                time);

        final CompletionException failed = assertThrows(CompletionException.class,
                () -> join(racing.begin(designated(BLUE)
                        .generation(TODAY)
                        .completedWhen("data/euc1")
                        .build())));

        final IOException unread = assertInstanceOf(IOException.class, failed.getCause());
        assertTrue(unread.getMessage().startsWith("Permission denied"), unread.getMessage());
        assertEquals(1, unread.getSuppressed().length, "The failed release must ride along");
        assertEquals("No route to the cluster", unread.getSuppressed()[0].getMessage());
    }

    // ---- the switch -----------------------------------------------------------------------------

    static List<String> switchesThatStopBlue() {
        return List.of(KEY, Switches.ownerKey(KEY, BLUE));
    }

    @ParameterizedTest
    @MethodSource("switchesThatStopBlue")
    void doesNotRunWhenSwitchedOff(final String switched) {
        put(ExclusiveRuns.switchKey(switched), new Switch(false, "patching", null).toJson());

        final Admission off = join(runs.begin(elected(BLUE).enabledBy(KEY).build()));

        assertEquals("patching", assertInstanceOf(Admission.Disabled.class, off).reason());
        assertEquals(switched.equals(KEY),
                join(runs.begin(elected(GREEN).enabledBy(KEY).build())) instanceof Admission.Disabled,
                "Switching one owner off leaves the others on");
    }

    // ---- completing -----------------------------------------------------------------------------

    @Test
    void publishesTheMilestoneBeforeItGivesTheLeaseBack() {
        final List<String> told = new ArrayList<>();
        final ExclusiveRuns theirRuns = new ExclusiveRuns(store, time, new PiplexObserver() {
            @Override
            public void milestonePublished(final RunRef run) {
                told.add("published");
            }

            @Override
            public void released(final RunRef run) {
                told.add("released");
            }
        });
        final Admitted held = assertInstanceOf(Admitted.class, join(theirRuns.begin(completing(BLUE).build())));

        final PublishResult result = join(held.completeAndRelease());

        assertEquals(PublishResult.Outcome.PUBLISHED, result.outcome());
        assertEquals(List.of("published", "released"), told);
        assertFalse(held.isHeld());
        assertInstanceOf(Admission.AlreadyCompleted.class, join(runs.begin(completing(GREEN).build())),
                "The next candidate must find the day done");
        assertInstanceOf(Admitted.class, join(runs.begin(elected(GREEN).build())), "And the lease free");
    }

    @Test
    void refusesToCompleteWithoutAMilestoneAndKeepsTheLease() {
        final Admitted held = assertInstanceOf(Admitted.class, join(runs.begin(elected(BLUE).build())));

        final CompletionException failed = assertThrows(CompletionException.class,
                () -> join(held.completeAndRelease()));

        assertInstanceOf(IllegalStateException.class, failed.getCause());
        assertTrue(held.isHeld(), "A call that could never have worked must not end the run");
    }

    @Test
    void doesNotPublishForARunThatWasRevoked() {
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class,
                join(runs.begin(completing(BLUE).designatedBy(KEY).build())));
        designate(GREEN);

        final CompletionException failed = assertThrows(CompletionException.class,
                () -> join(held.completeAndRelease()));

        assertInstanceOf(IllegalStateException.class, failed.getCause());
        assertNull(join(milestones.current("data/euc1")), "Nothing may be published for a revoked run");
        assertInstanceOf(Admitted.class, join(runs.begin(designated(GREEN).build())),
                "The lease still goes back");
    }

    @Test
    void givesTheLeaseBackWhenThePublishFails() {
        final Admitted held = assertInstanceOf(Admitted.class, join(runs.begin(completing(BLUE).build())));
        // Hand-edited while the run was working.
        put(Milestones.keyOf("data/euc1"), "not a milestone");

        assertThrows(CompletionException.class, () -> join(held.completeAndRelease()));

        assertInstanceOf(Admitted.class, join(runs.begin(elected(GREEN).build())));
    }

    @Test
    void keepsTheLeaseUntilAPublishOverlappedByARevocationIsSettled() {
        final CompletableFuture<Void> gate = new CompletableFuture<>();
        final CoordinationStore slowPublish = publishingAfter(gate);
        final ExclusiveRuns theirRuns = new ExclusiveRuns(slowPublish, time);
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class,
                join(theirRuns.begin(completing(BLUE).designatedBy(KEY).build())));
        final List<CompletionStage<Void>> releasedByListener = new ArrayList<>();
        // What a host does: stop the work, then give the lease back.
        held.onRevoked(revocation -> releasedByListener.add(held.release()));

        final CompletableFuture<PublishResult> completed = held.completeAndRelease().toCompletableFuture();
        designate(GREEN);

        assertEquals(1, releasedByListener.size(), "The revocation must have been told");
        assertFalse(releasedByListener.get(0).toCompletableFuture().isDone());
        assertInstanceOf(Admission.HeldByOther.class, join(runs.begin(elected(GREEN).build())),
                "The lease must stand while the milestone is on its way");

        gate.complete(null);

        assertEquals(PublishResult.Outcome.PUBLISHED, join(completed).outcome());
        join(releasedByListener.get(0));
        assertInstanceOf(Admission.AlreadyCompleted.class,
                join(runs.begin(completing(GREEN).designatedBy(KEY).build())),
                "The next owner must find the day done");
    }

    @Test
    void refusesASecondCompletionWhileTheFirstIsUnderWay() {
        final CompletableFuture<Void> gate = new CompletableFuture<>();
        final CoordinationStore slowPublish = publishingAfter(gate);
        final Admitted held = assertInstanceOf(Admitted.class,
                join(new ExclusiveRuns(slowPublish, time).begin(completing(BLUE).build())));

        final CompletableFuture<PublishResult> first = held.completeAndRelease().toCompletableFuture();
        final CompletionException second = assertThrows(CompletionException.class,
                () -> join(held.completeAndRelease()));

        assertInstanceOf(IllegalStateException.class, second.getCause());
        gate.complete(null);
        assertEquals(PublishResult.Outcome.PUBLISHED, join(first).outcome());
    }

    // ---- handover while a run is in flight ------------------------------------------------------

    @Test
    void revokesTheHolderWhenTheDesignationChanges() {
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class, join(runs.begin(designated(BLUE).build())));
        final List<Revocation> seen = revocations(held);

        assertTrue(held.isHeld());
        designate(GREEN);

        assertFalse(held.isHeld(), "The holder must stop believing it owns the work");
        assertEquals(1, seen.size());
        assertEquals(Revocation.Reason.DESIGNATION_CHANGED, seen.get(0).reason());
        assertEquals(GREEN, seen.get(0).newOwner());
    }

    @Test
    void keepsTheLeaseUntilARevokedHolderHasStopped() {
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class, join(runs.begin(designated(BLUE).build())));

        designate(GREEN);
        assertFalse(held.isHeld());

        // The host has been told to stop, and stopping takes a while: until it says so, the work may
        // still be running, and nobody else may start it.
        assertInstanceOf(Admission.HeldByOther.class, join(runs.begin(designated(GREEN).build())),
                "A revoked holder's lease must stand until the work has stopped");

        // Nothing renews a revoked lease, though, so it lapses within its term.
        time.advance(LEASE);
        assertInstanceOf(Admitted.class, join(runs.begin(designated(GREEN).build())));
    }

    @Test
    void leavesAnAbandonedLeaseForTheSameRunToRetake() {
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class, join(runs.begin(designated(BLUE).build())));
        final List<Revocation> told = new ArrayList<>();
        held.onRevoked(told::add);

        held.abandon();
        join(held.release());
        time.advance(LEASE.dividedBy(2));

        // A host going away comes back as the same run, and a new token would fence out its own work.
        final Admitted back = assertInstanceOf(Admitted.class, join(runs.begin(designated(BLUE).build())));
        assertEquals(held.fencingToken(), back.fencingToken());
        assertFalse(held.isHeld());
        assertTrue(told.isEmpty(), "Abandoning is not a revocation");
    }

    @Test
    void stopsRenewingAnAbandonedLease() {
        final Admitted held = assertInstanceOf(Admitted.class, join(runs.begin(elected(BLUE).build())));

        held.abandon();
        assertInstanceOf(Admission.HeldByOther.class, join(runs.begin(elected(GREEN).build())));
        time.advance(LEASE);

        assertInstanceOf(Admitted.class, join(runs.begin(elected(GREEN).build())),
                "Nothing renews an abandoned lease, so it lapses within its term");
    }

    @Test
    void handsOverToACandidateParkedWaitingForIt() {
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class, join(runs.begin(designated(BLUE).build())));

        final CompletableFuture<Admission> parked = runs.begin(designated(GREEN)
                .handoverWait(Duration.ofHours(4))
                .build()).toCompletableFuture();
        assertFalse(parked.isDone(), "Not designated, so it waits rather than exits");

        designate(GREEN);
        assertFalse(held.isHeld());
        assertFalse(parked.isDone(), "Not while the revoked holder may still be running");

        join(held.release());
        time.advance(LEASE);

        assertTrue(parked.isDone(), "The new owner must pick it up without waiting for a schedule");
        assertInstanceOf(Admitted.class, parked.join());
    }

    @Test
    void picksUpALeaseGivenBackSoonAfterTheDesignationMoved() {
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class, join(runs.begin(designated(BLUE).build())));
        final CompletableFuture<Admission> parked = runs.begin(designated(GREEN)
                .handoverWait(Duration.ofHours(4))
                .build()).toCompletableFuture();

        designate(GREEN);
        time.advance(Duration.ofSeconds(1));
        join(held.release());

        // Neither a whole round nor the rest of the lease: the holder stopped in a second.
        time.advance(Duration.ofSeconds(2));
        assertTrue(parked.isDone(), "A lease given back right after a handover must be noticed at once");
        assertInstanceOf(Admitted.class, parked.join());
    }

    @Test
    void slowsDownToWholeRoundsWhenTheLeaseIsNotGivenBack() {
        final AtomicInteger acquires = new AtomicInteger();
        designate(BLUE);
        assertInstanceOf(Admitted.class, join(runs.begin(designated(BLUE).build())));
        final CompletableFuture<Admission> parked = new ExclusiveRuns(counting("tryAcquire", acquires), time)
                .begin(designated(GREEN).handoverWait(Duration.ofHours(4)).build())
                .toCompletableFuture();

        // Asked at once, then after 0.5s, 1.5s, 3.5s, 7.5s, 15.5s and 31.5s -- and then not before the
        // lease lapses, since the next short wait would be no shorter than a round.
        designate(GREEN);
        time.advance(LEASE.minusSeconds(1));
        assertEquals(7, acquires.get());
        assertFalse(parked.isDone());

        time.advance(LEASE);
        assertInstanceOf(Admitted.class, parked.join());
    }

    @Test
    void doesNotHurryWhileTheDesignationNamesSomebodyElse() {
        final AtomicInteger rounds = new AtomicInteger();
        designate(BLUE);
        final CompletableFuture<Admission> parked = new ExclusiveRuns(store, time, new PiplexObserver() {
            @Override
            public void parked(final RunRef run, final String waitingOn, final Duration remaining) {
                rounds.incrementAndGet();
            }
        }).begin(designated(GREEN).handoverWait(Duration.ofHours(4)).build()).toCompletableFuture();

        // The watch on the designation says when it names this candidate; looking sooner learns nothing.
        designate("apac1-blue");
        time.advance(request(LEASE).renewEvery().minusSeconds(1));
        assertEquals(2, rounds.get());
        assertFalse(parked.isDone());
        parked.cancel(false);
    }

    @Test
    void hurriesAlsoWhenOnlyTheRoundNoticedTheMove() {
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class, join(runs.begin(designated(BLUE).build())));
        final CompletableFuture<Admission> parked = new ExclusiveRuns(blindWhileWaiting(), time)
                .begin(designated(GREEN).handoverWait(Duration.ofHours(4)).build())
                .toCompletableFuture();

        // A watch polled seldom hears nothing; the round finds the move when it re-reads at 20s, while
        // the holder is still stopping.
        designate(GREEN);
        time.advance(Duration.ofSeconds(21));
        join(held.release());

        time.advance(Duration.ofSeconds(1));
        assertTrue(parked.isDone(), "A move the round found must hurry like one a watch reported");
        assertInstanceOf(Admitted.class, parked.join());
    }

    @Test
    void watchesUrgentlyOnlyWhatAnAdmittedRunActsOn() {
        final List<Watch> asked = new ArrayList<>();
        final ExclusiveRuns recorded = new ExclusiveRuns(intercepting((proxy, method, args) -> {
            if (method.getName().equals("awaitChange") && args.length == 4) {
                asked.add((Watch) args[3]);
            }
            return method.invoke(store, args);
        }), time);
        designate(BLUE);

        assertInstanceOf(Admitted.class, join(recorded.begin(designated(BLUE).enabledBy(KEY).build())));
        // The designation, the switch, and the switch for this owner alone.
        assertEquals(List.of(Watch.URGENT, Watch.URGENT, Watch.URGENT), asked);

        asked.clear();
        recorded.begin(designated(GREEN).enabledBy(KEY).handoverWait(Duration.ofHours(4)).build());
        assertEquals(List.of(Watch.BACKGROUND, Watch.BACKGROUND, Watch.BACKGROUND), asked);
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
        assertFalse(parked.isDone(), "Still nobody else's turn");
        assertEquals(afterAnHour, time.pending(),
                "A second hour of waiting must cost exactly what the first one did");
        assertTrue(afterAnHour <= 8,
                "And that must be a handful of timers, not one per round: " + afterAnHour);
    }

    @Test
    void refusesASecondAskByTheSameRunWhenItSaysWhichAskItIs() {
        designate(BLUE);
        final Admitted first = assertInstanceOf(Admitted.class,
                join(runs.begin(designated(BLUE, "#7").executionId("11").build())));

        // One run, two asks at once: two branches of a parallel stage, or a block inside another. Told
        // apart, the second is contention like anybody else's; not told apart, both would be let in on
        // the same lease and the first to finish would give it away from under the other.
        final Admission second = join(runs.begin(designated(BLUE, "#7").executionId("19").build()));
        assertEquals(BLUE + "/" + BLUE + "#7/11",
                assertInstanceOf(Admission.HeldByOther.class, second).heldBy());

        // And it is still one lease per ask, so a retry of that same ask recovers its own.
        join(first.release());
        assertInstanceOf(Admitted.class,
                join(runs.begin(designated(BLUE, "#7").executionId("19").build())));
    }

    @Test
    void tellsALateListenerThatOwnershipIsAlreadyGone() {
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class, join(runs.begin(designated(BLUE).build())));
        designate(GREEN);

        final List<Revocation> seen = revocations(held);
        assertEquals(1, seen.size(), "Registering after the fact must not lose the notification");
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

        assertTrue(held.isHeld(), "Renewal must outlive the lease it is renewing");
        assertInstanceOf(Admission.HeldByOther.class, join(runs.begin(designated(BLUE, "#2").build())));
    }

    @ParameterizedTest
    @MethodSource("switchesThatStopBlue")
    void stopsTheRunWhenItIsSwitchedOffUnderneath(final String switched) {
        final Admitted held = assertInstanceOf(Admitted.class,
                join(runs.begin(elected(BLUE).enabledBy(KEY).build())));
        final List<Revocation> seen = revocations(held);

        put(ExclusiveRuns.switchKey(switched), new Switch(false, "incident 4711", null).toJson());

        assertFalse(held.isHeld());
        // Whoever finds the stopped build has to be told which incident, not sent to ask somebody.
        assertEquals(List.of(new Revocation(Revocation.Reason.DISABLED, null, "incident 4711")), seen);
    }

    @Test
    void stopsTheRunWhenItsDesignationIsRemovedAltogether() {
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class, join(runs.begin(designated(BLUE).build())));
        final List<Revocation> seen = revocations(held);

        remove(Designations.keyOf(KEY));

        // An absent key is "nobody is designated", and that is what a candidate asking from scratch is
        // told. A run in flight has to be told the same thing, or who may run depends on when the run
        // happened to start.
        assertFalse(held.isHeld(), "A run nobody is designated for must not keep running");
        assertEquals(1, seen.size());
        assertEquals(Revocation.Reason.DESIGNATION_CHANGED, seen.get(0).reason());
        assertNull(seen.get(0).newOwner(), "Nobody takes over from a designation that was removed");
        assertTrue(seen.get(0).detail().contains(Designations.keyOf(KEY)),
                "The revocation has to name the key, was: " + seen.get(0));
    }

    static List<RenewalOutage> renewalOutages() {
        final Duration second = Duration.ofSeconds(1);
        return List.of(
                new RenewalOutage("Grace shorter than the lease", UnreachableStore::stopAnsweringRenewals,
                        Duration.ofSeconds(30), Duration.ofSeconds(25), Duration.ofSeconds(35)),
                // The grace period says how long not knowing is tolerated, not how long the lease lasts.
                new RenewalOutage("Grace longer than the lease", UnreachableStore::stopAnsweringRenewals,
                        Duration.ofHours(1), LEASE.minusSeconds(5), LEASE.plusSeconds(5)),
                // Then the grace period and the term end at the same instant, which must not read as a
                // lease that simply ran out.
                new RenewalOutage("Grace left at its default", UnreachableStore::stopAnsweringRenewals,
                        null, LEASE.minusSeconds(5), LEASE.plus(second)),
                // An open, silent connection: nothing fails, so only the run's own reckoning ends it.
                new RenewalOutage("Every call taken and none answered", UnreachableStore::goSilent,
                        Duration.ofHours(1), LEASE.minusSeconds(5), LEASE.plus(second)));
    }

    @ParameterizedTest
    @MethodSource("renewalOutages")
    void givesUpTheRunWhenItsLeaseCannotBeRenewed(final RenewalOutage outage) {
        final UnreachableStore flaky = new UnreachableStore(store);
        final ExclusiveRequest.Builder request = elected(BLUE);
        if (outage.grace() != null) {
            request.renewalGrace(outage.grace());
        }
        final Admitted held = assertInstanceOf(Admitted.class,
                join(new ExclusiveRuns(flaky, time).begin(request.build())));
        final List<Revocation> seen = revocations(held);

        outage.breaks().accept(flaky);
        time.advance(outage.heldAt());
        assertTrue(held.isHeld(), "A blink of the store must not kill a run in progress");

        time.advance(outage.goneAt().minus(outage.heldAt()));

        assertFalse(held.isHeld(), "But acting as the owner on no evidence is the worse mistake");
        // Whichever deadline came first, what happened is that nothing was heard back -- not a lease
        // somebody else took, which sends people looking for a second run instead of at the cluster.
        assertEquals(Revocation.Reason.RENEWAL_FAILED, seen.get(0).reason());
        assertTrue(seen.get(0).detail().contains("could not be reached"), seen.get(0).detail());
    }

    @Test
    void givesUpWhenTheLeaseItRecoveredHadAlmostRunOut() {
        // A lease taken by an attempt whose outcome was never learnt: nobody is renewing it, and it has
        // been running for fifty of its sixty seconds by the time the same run asks again. What comes
        // back is that lease, not a new one, and what is left of it is the whole term this run gets.
        // Read as a fresh lease -- which is what dropping the figure the store reports amounts to -- the
        // run would first try to renew a minute later, and spend the fifty seconds in between believing
        // it owned work anybody else was free to take.
        final ExclusiveRequest request = elected(BLUE).renewalGrace(Duration.ofHours(1)).build();
        join(store.tryAcquire(ExclusiveRuns.leaseKey(KEY), ExclusiveRuns.leaseOwner(request), LEASE));
        time.advance(LEASE.minusSeconds(10));

        final UnreachableStore flaky = new UnreachableStore(store);
        final Admitted recovered = assertInstanceOf(
                Admitted.class, join(new ExclusiveRuns(flaky, time).begin(request)));
        final List<Revocation> seen = revocations(recovered);
        flaky.stopAnsweringRenewals();

        time.advance(Duration.ofSeconds(5));
        assertTrue(recovered.isHeld());

        time.advance(Duration.ofSeconds(6));
        assertFalse(recovered.isHeld(), "What was left of the recovered lease is the whole term");
        assertEquals(Revocation.Reason.RENEWAL_FAILED, seen.get(0).reason());
    }

    @Test
    void failsAnAskWhichTheStoreNeverAnswers() {
        final CompletableFuture<Admission> asked = new ExclusiveRuns(
                new SilentStore(CoordinationStore.DEFAULT_RESPONSE_BOUND).store(), time)
                .begin(designated(BLUE).build()).toCompletableFuture();

        time.advance(CoordinationStore.DEFAULT_RESPONSE_BOUND.minusMillis(1));
        assertFalse(asked.isDone());
        time.advance(Duration.ofMillis(1));

        assertTrue(asked.isDone(), "A store which never answers must not hold an ask for good");
        final CompletionException thrown = assertThrows(CompletionException.class, asked::join);
        assertInstanceOf(TimeoutException.class, thrown.getCause());
    }

    @Test
    void givesUpTheRunWhenTheLeaseItWasHoldingTurnsOutToBeGone() {
        final UnreachableStore flaky = new UnreachableStore(store);
        final ExclusiveRuns theirRuns = new ExclusiveRuns(flaky, time);

        final Admitted held = assertInstanceOf(Admitted.class, join(theirRuns.begin(
                elected(BLUE).renewalGrace(Duration.ofHours(1)).build())));
        final List<Revocation> seen = revocations(held);

        // The store answers, and what it says is no: the lease it was asked to extend is not this
        // caller's any more. Nothing an unreachable store can ever say, and nothing a grace period is
        // for -- there is no uncertainty left to tolerate.
        flaky.refuseRenewals();
        time.advance(LEASE.dividedBy(3));

        assertFalse(held.isHeld(), "A refused renewal is somebody else's lease, not a blink of the store");
        assertEquals(Revocation.Reason.LEASE_LOST, seen.get(0).reason());
        assertNull(seen.get(0).detail(), "A refusal is definite, and has nothing to add to the reason");
    }

    // ---- a guard which stops making sense ------------------------------------------------------

    static List<String> keysARunWatches() {
        return List.of(Designations.keyOf(KEY), Switches.keyOf(KEY));
    }

    @ParameterizedTest
    @MethodSource("keysARunWatches")
    void stopsTheRunWhenAKeyItIsWatchingStopsBeingReadable(final String key) {
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class,
                join(runs.begin(designated(BLUE).enabledBy(KEY).build())));
        final List<Revocation> seen = revocations(held);

        // Somebody editing the key by hand and getting it wrong. What this is against is a run which looks
        // supervised and is not: a watch whose parse blew up was never armed again.
        overwrite(key, "{\"owner\": ");

        assertFalse(held.isHeld(), "A run whose guard cannot be read must not keep running");
        assertEquals(1, seen.size());
        assertEquals(Revocation.Reason.GUARD_UNREADABLE, seen.get(0).reason());
        assertTrue(seen.get(0).detail().contains(key),
                "The revocation has to name the key somebody must go and fix, was: " + seen.get(0));
    }

    static List<String> keysAnAskReads() {
        return List.of(Designations.keyOf(KEY), Switches.keyOf(KEY), Milestones.keyOf("data/euc1"));
    }

    @ParameterizedTest
    @MethodSource("keysAnAskReads")
    void refusesToRunWhenAKeyCannotBeReadInTheFirstPlace(final String key) {
        designate(BLUE);
        overwrite(key, "{\"owner\": ");

        final Admission refused = join(runs.begin(completing(BLUE).designatedBy(KEY).enabledBy(KEY).build()));

        // The same operator error as GUARD_UNREADABLE, found a moment earlier, and not a stack trace.
        final Admission.GuardUnreadable unreadable = assertInstanceOf(Admission.GuardUnreadable.class, refused);
        assertEquals(key, unreadable.key());
        assertTrue(unreadable.detail().contains(key), unreadable.detail());
    }

    @Test
    void doesNotParkOnAKeyNobodyCanRead() {
        put(Designations.keyOf(KEY), "{\"owner\": ");

        final CompletableFuture<Admission> asked = runs.begin(designated(BLUE)
                .handoverWait(Duration.ofHours(4))
                .build()).toCompletableFuture();

        // Parking waits for a change, and the same bytes read again say the same thing. Somebody has to
        // go and fix the key, and four hours of waiting is four hours of not saying so.
        assertTrue(asked.isDone(), "An unreadable key is not something to wait out");
        assertInstanceOf(Admission.GuardUnreadable.class, asked.join());
    }

    @Test
    void doesNotSpinOnAWatchWhichCannotReachItsKey() {
        final UnreachableStore flaky = new UnreachableStore(store);
        designate(BLUE);
        flaky.stopAnsweringWatches();

        final CompletableFuture<Admission> parked = new ExclusiveRuns(flaky, time)
                .begin(designated(GREEN).handoverWait(Duration.ofHours(1)).build())
                .toCompletableFuture();

        // A watch which failed is not a change. Woken by one, a round would re-read and watch again
        // with nothing in between, and an hour of handover would go into asking a store that cannot
        // answer as fast as the thread allows.
        assertFalse(parked.isDone(), "The handover window is not over");
        assertEquals(1, flaky.watchesAsked());

        time.advance(LEASE.dividedBy(3));
        assertEquals(2, flaky.watchesAsked(), "One look per round, and the round is what the timer says");
    }

    @Test
    void givesTheLeaseBackWhenNobodyCanBeToldAboutIt() {
        final ExclusiveRuns theirRuns = new ExclusiveRuns(store, time, new PiplexObserver() {
            @Override
            public void admitted(final RunRef run, final long fencingToken) {
                throw new IllegalStateException("The build log is closed");
            }
        });

        final CompletionException failed = assertThrows(CompletionException.class,
                () -> join(theirRuns.begin(elected(BLUE).build())));

        // The caller got the throw instead of the handle, so nothing is left that would ever release
        // the lease, and it would go on being renewed until the controller stopped.
        assertInstanceOf(IllegalStateException.class, failed.getCause());
        assertInstanceOf(Admitted.class, join(runs.begin(elected(GREEN).build())));
    }

    @Test
    void givesTheLeaseBackWhenTheObserverThrowsOnAFinishedDay() {
        designate(BLUE);
        final ExclusiveRuns theirRuns = new ExclusiveRuns(
                new RacedAcquireStore(store,
                        () -> join(milestones.publish("data/euc1", TODAY, GREEN, "eod#9"))),
                time, new PiplexObserver() {
                    @Override
                    public void alreadyCompleted(final RunRef run, final Generation reached) {
                        throw new IllegalStateException("The build log is closed");
                    }
                });

        assertThrows(CompletionException.class, () -> join(theirRuns.begin(designated(BLUE)
                .generation(TODAY)
                .completedWhen("data/euc1")
                .build())));

        assertInstanceOf(Admitted.class, join(runs.begin(elected(GREEN).build())),
                "A lease taken for a refused run must go back even when nobody could be told");
    }

    @Test
    void givesTheLeaseBackWhenAListenerThrowsOnTheWayOut() {
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class, join(runs.begin(designated(BLUE).build())));
        held.onRevoked(revocation -> {
            throw new IllegalStateException("The build this was stopping is already gone");
        });
        final List<Revocation> seen = revocations(held);

        designate(GREEN);

        // One host that throws is one thing to go and fix, not three. The others are still told, the
        // lease still goes back once released, and the release says what the listener threw.
        assertEquals(1, seen.size(), "The listeners after it are still owed the revocation");
        assertEquals(Revocation.Reason.DESIGNATION_CHANGED, seen.get(0).reason());
        final CompletionException failed = assertThrows(CompletionException.class, () -> join(held.release()));
        assertInstanceOf(IllegalStateException.class, failed.getCause());
        assertInstanceOf(Admitted.class, join(runs.begin(elected(GREEN).build())));
    }

    @Test
    void tellsARevocationWithNothingLockedAndTheListenersFirst() {
        final List<String> told = new ArrayList<>();
        final AtomicReference<Admitted> admitted = new AtomicReference<>();
        final ExclusiveRuns theirRuns = new ExclusiveRuns(store, time, new PiplexObserver() {
            @Override
            public void revoked(final RunRef run, final Revocation cause) {
                onAnotherThread(() -> join(admitted.get().release()));
                told.add("observer");
            }
        });
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class, join(theirRuns.begin(designated(BLUE).build())));
        admitted.set(held);
        held.onRevoked(revocation -> {
            // The host stops its work from here, and whatever that takes must not wait on piplex.
            onAnotherThread(() -> join(held.release()));
            told.add("listener");
        });

        designate(GREEN);

        assertEquals(List.of("listener", "observer"), told);
        assertInstanceOf(Admitted.class, join(runs.begin(designated(GREEN).build())));
    }

    @Test
    void tellsAnAdmissionBeforeARevocationWhichFollowsItAtOnce() {
        final List<String> told = new ArrayList<>();
        final AtomicInteger watches = new AtomicInteger();
        final CoordinationStore moving = intercepting((proxy, method, args) -> {
            // Moved once the lease is held and the guards read, as the designation watch starts.
            if (method.getName().equals("awaitChange")
                    && args[0].equals(ExclusiveRuns.designationKey(KEY))
                    && watches.getAndIncrement() == 0) {
                designate(GREEN);
            }
            return method.invoke(store, args);
        });
        final ExclusiveRuns theirRuns = new ExclusiveRuns(moving, time, new PiplexObserver() {
            @Override
            public void admitted(final RunRef run, final long fencingToken) {
                told.add("admitted");
            }

            @Override
            public void revoked(final RunRef run, final Revocation cause) {
                told.add("revoked");
            }
        });
        designate(BLUE);

        final Admitted held = assertInstanceOf(Admitted.class, join(theirRuns.begin(designated(BLUE).build())));

        assertFalse(held.isHeld());
        assertEquals(List.of("admitted", "revoked"), told, "A log must not say a run lost what it never had");
    }

    @Test
    void stillSaysTheStoreFailedWhenTheObserverThrowsOnRelease() {
        final UnreachableStore failing = new UnreachableStore(store);
        final ExclusiveRuns theirRuns = new ExclusiveRuns(failing, time, new PiplexObserver() {
            @Override
            public void released(final RunRef run) {
                throw new IllegalStateException("The build log is closed");
            }
        });
        final Admitted held = assertInstanceOf(Admitted.class, join(theirRuns.begin(elected(BLUE).build())));

        failing.stopAnsweringReleases();
        final CompletionException failed = assertThrows(CompletionException.class, () -> join(held.release()));

        // The observer's throw is the one reported, but the store's is what says the lease may still stand.
        final IllegalStateException thrown = assertInstanceOf(IllegalStateException.class, failed.getCause());
        assertEquals(1, thrown.getSuppressed().length);
        assertInstanceOf(IOException.class, thrown.getSuppressed()[0]);
    }

    @Test
    void leavesNoGuardTimerBehindARelease() {
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class, join(runs.begin(designated(BLUE)
                .enabledBy(KEY)
                .guardGrace(Duration.ofHours(4))
                .build())));

        join(held.release());
        // The watches are not called off, and are over within one of their waits.
        time.advance(LEASE);

        assertEquals(0, time.pending(), "A released run must not be kept for the length of its grace period");
    }

    @Test
    void doesNotTellASecondCallerTheLeaseIsBackBeforeItIs() {
        final UnreachableStore silent = new UnreachableStore(store);
        final Admitted held = assertInstanceOf(Admitted.class,
                join(new ExclusiveRuns(silent, time).begin(elected(BLUE).build())));

        silent.goSilent();
        final CompletableFuture<Void> first = held.release().toCompletableFuture();
        final CompletableFuture<Void> second = held.release().toCompletableFuture();

        // Asking twice is one release, and the second ask does not get to answer for the store. A
        // controller handing over waits on exactly this stage before it lets the next run in, and
        // "already done" there is a lease still standing.
        assertFalse(first.isDone(), "The store has not said the lease is back");
        assertFalse(second.isDone(), "And the second caller must not be told it has");
    }

    static List<Outage> activeOutages() {
        return List.of(
                new Outage("Reads fail", store -> store.stopAnsweringReadsOf(ACTIVE)),
                new Outage("Nothing is answered", store -> store.goSilentOn(ACTIVE)));
    }

    static List<Outage> guardOutages() {
        final String designation = Designations.keyOf(KEY);
        return List.of(
                new Outage("Reads fail", store -> store.stopAnsweringReadsOf(designation)),
                new Outage("Nothing is answered", store -> store.goSilentOn(designation)),
                // An unconfirmed watch answer carries an observation up to a window old, and counts for
                // exactly as much as a failed watch.
                new Outage("Watches are unconfirmed and reads fail", store -> {
                    store.stopConfirmingWatches();
                    store.stopAnsweringPlainReadsOf(designation);
                }));
    }

    @ParameterizedTest
    @MethodSource("guardOutages")
    void givesTheRunUpWhenNothingCanBeLearntAboutTheDesignationForTooLong(final Outage outage) {
        final UnreachableStore flaky = new UnreachableStore(store);
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class,
                join(new ExclusiveRuns(flaky, time).begin(designated(BLUE)
                        .guardGrace(Duration.ofSeconds(30))
                        .build())));
        final List<Revocation> seen = revocations(held);

        // One key only: the lease is renewed throughout, so what ends the run has to be the guard's own
        // grace period.
        outage.breaks().accept(flaky);
        // The watch in flight answers once more when its wait, half the grace period, is out. The grace
        // period is counted from that last answer.
        time.advance(Duration.ofSeconds(15));

        time.advance(Duration.ofSeconds(25));
        assertTrue(held.isHeld(), "A blink must not kill a run whose lease is being renewed");

        time.advance(Duration.ofSeconds(10));

        assertFalse(held.isHeld(), "A run nothing can be learnt about must not keep running");
        assertEquals(Revocation.Reason.GUARD_UNREACHABLE, seen.get(0).reason());
        assertTrue(seen.get(0).detail().contains(Designations.keyOf(KEY)),
                "The revocation has to name the key nobody could read, was: " + seen.get(0));
    }

    @Test
    void leavesNoPacingTimerBehindARelease() {
        final UnreachableStore flaky = new UnreachableStore(store);
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class,
                join(new ExclusiveRuns(flaky, time).begin(designated(BLUE)
                        .guardGrace(Duration.ofSeconds(30))
                        .build())));

        // The watch in flight answers, the next one and the read behind it fail, and the guard is left
        // waiting on the timer that paces its next attempt.
        flaky.stopAnsweringReadsOf(Designations.keyOf(KEY));
        time.advance(Duration.ofSeconds(15));
        assertTrue(held.isHeld());

        join(held.release());

        assertEquals(0, time.pending(), "A released admission must leave no timer behind it");
    }

    @Test
    void keepsWatchingAGuardWhenTheStoreRefusesTheCallOutright() {
        final UnreachableStore closed = new UnreachableStore(store);
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class,
                join(new ExclusiveRuns(closed, time).begin(designated(BLUE)
                        .guardGrace(Duration.ofSeconds(30))
                        .build())));
        final List<Revocation> seen = revocations(held);

        // A client closed under a run in flight -- which is what saving the settings on a controller
        // does -- refuses the call outright rather than failing the stage it returned. That throw is on
        // a scheduler's thread or inside a future either way, so nothing reports it and the watch is
        // simply never armed again, while the renewal loop keeps the lease alive over work nothing is
        // left watching. A read is one round trip and lands, so the loop has somewhere to go.
        closed.refuseWatchesOutright();
        time.advance(Duration.ofSeconds(15));

        designate(GREEN);
        time.advance(Duration.ofSeconds(16));

        assertFalse(held.isHeld(), "A refused call must not be the end of the guard loop");
        assertEquals(Revocation.Reason.DESIGNATION_CHANGED, seen.get(0).reason());
        assertEquals(GREEN, seen.get(0).newOwner());
    }

    @Test
    void noticesAHandoverThroughAPlainReadWhenNoWatchEverAnswers() {
        final UnreachableStore flaky = new UnreachableStore(store);
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class,
                join(new ExclusiveRuns(flaky, time).begin(designated(BLUE).build())));
        final List<Revocation> seen = revocations(held);

        flaky.stopAnsweringWatches();
        designate(GREEN);
        time.advance(LEASE.dividedBy(3));

        // The read after a failed watch is not a health check that happens to succeed: it is the answer
        // the watch was waiting for. A guard which only counted failures would sit on a designation
        // that has already moved, for as long as its grace period let it.
        assertFalse(held.isHeld(), "A handover is a handover however it was learnt");
        assertEquals(Revocation.Reason.DESIGNATION_CHANGED, seen.get(0).reason());
        assertEquals(GREEN, seen.get(0).newOwner());
    }

    @ParameterizedTest
    @EnumSource(value = Revocation.Reason.class, names = {"DESIGNATION_CHANGED", "DISABLED"})
    void picksAGuardBackUpAfterLosingSightOfIt(final Revocation.Reason reason) {
        final UnreachableStore flaky = new UnreachableStore(store);
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class,
                join(new ExclusiveRuns(flaky, time).begin(designated(BLUE).enabledBy(KEY).build())));
        final List<Revocation> seen = revocations(held);

        flaky.stopAnsweringWatches();
        time.advance(LEASE.multipliedBy(5));
        assertTrue(held.isHeld(), "A watch which cannot reach its key says nothing about who owns this");

        flaky.answerWatchesAgain();
        time.advance(LEASE);
        if (reason == Revocation.Reason.DISABLED) {
            put(ExclusiveRuns.switchKey(KEY), new Switch(false, "incident 4711", null).toJson());
        } else {
            designate(GREEN);
        }

        // The watch has to have re-armed itself: nothing else in a run in flight ever looks at the key.
        assertFalse(held.isHeld(), "A change after the blink must still be noticed");
        assertEquals(reason, seen.get(0).reason());
    }

    @Test
    void keepsARunWhoseExpiryTimerFiredJustAsARenewalLanded() {
        final LateTimers timers = new LateTimers(time);
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class,
                join(new ExclusiveRuns(store, timers).begin(designated(BLUE).build())));
        final List<Revocation> seen = revocations(held);

        // A renewal lands, which moves the end of the run forward and calls off the timer armed for the
        // old one. Calling a timer off is not stopping one already running, though, so that old timer
        // gets to ask its question anyway -- and the lease it would end has another minute on it.
        time.advance(request(LEASE).renewEvery());
        timers.runTheTimerThatWasTooLateToCancel();

        assertTrue(held.isHeld(), "A lease that has just been extended must not be given up");
        assertTrue(seen.isEmpty(), "And nothing may be told that it was: " + seen);
    }

    @Test
    void stillEndsTheRunWhenTheSchedulerRefusesToArmTheGiveUpTimerAgain() {
        final RefusingTimers timers = new RefusingTimers(time);
        final Admitted held = assertInstanceOf(Admitted.class,
                join(new ExclusiveRuns(store, timers).begin(elected(BLUE).build())));
        final List<Revocation> seen = revocations(held);

        // A controller shutting down: every schedule from here on is refused. The renewal in flight
        // lands and asks for a later deadline, and there is nothing left to arm for it. Put in force
        // anyway, that deadline would be one the timer which survived does not recognise -- it fires,
        // finds nothing due, and returns without re-arming, leaving the run holding a lease with
        // nothing left to revoke it and nothing to renew it either.
        timers.shutDown();
        time.advance(LEASE);

        assertFalse(held.isHeld(), "A run whose timers cannot be re-armed must still end");
        assertEquals(1, seen.size(), "And whoever is running the work has to be told: " + seen);
    }

    @Test
    void endsTheRunWhenTheSchedulerRefusesToArmTheGuardTimerAgain() {
        final RefusingTimers timers = new RefusingTimers(time);
        designate(BLUE);
        final Admitted held = assertInstanceOf(Admitted.class,
                join(new ExclusiveRuns(store, timers).begin(designated(BLUE).build())));
        final List<Revocation> seen = revocations(held);

        // The guard learns something about its key, which is the one place it re-arms -- and cannot.
        // Left to itself that throw goes into the whenComplete of a watch, where nothing reports it,
        // and the guard loop is over without a word while the lease goes on being renewed.
        timers.shutDown();
        designate(BLUE + "-again");

        assertFalse(held.isHeld(), "A guard which can no longer be re-armed is not a guard");
        assertEquals(Revocation.Reason.GUARD_UNREACHABLE, seen.get(0).reason());
        assertTrue(seen.get(0).detail().contains(Designations.keyOf(KEY)), seen.get(0).detail());
    }

    @Test
    void givesTheLeaseBackWhenTheStoreRefusesTheMilestoneReadOutright() {
        designate(BLUE);
        final UnreachableStore closed = new UnreachableStore(store);
        // A store closed under a run in flight -- which is what saving the settings does -- refuses the
        // call rather than failing the stage it would have returned. The read this fails is the one made
        // with the lease already in hand, and a throw before there is any stage escapes the handler that
        // gives the lease back: a whole lease in which nobody is admitted, held by nobody.
        final ExclusiveRuns racing = new ExclusiveRuns(
                new RacedAcquireStore(closed, closed::refuseReadsOutright), time);

        assertThrows(RuntimeException.class, () -> join(racing.begin(designated(BLUE)
                .generation(TODAY)
                .completedWhen("data/euc1")
                .build())));

        assertInstanceOf(Admitted.class, join(runs.begin(elected(GREEN).build())),
                "The lease taken a moment before must not be left to lapse");
    }

    // ---- an ask nobody is waiting on any more ----------------------------------------------------

    @Test
    void stopsParkingWhenNobodyIsWaitingForTheAnswerAnyMore() {
        designate(BLUE);
        final CompletableFuture<Admission> parked = runs.begin(designated(GREEN)
                .handoverWait(Duration.ofHours(4))
                .build()).toCompletableFuture();
        assertFalse(parked.isDone());

        // The build was cancelled. Nothing is left to tell, and three keys re-read every renewEvery
        // until four hours are out is work nobody asked for.
        parked.cancel(false);
        time.advance(Duration.ofMinutes(1));

        assertEquals(0, time.pending(), "An abandoned park must leave no timer behind it");
    }

    @Test
    void keepsOneWatchPerKeyHoweverOftenAParkIsWoken() {
        final AtomicInteger out = new AtomicInteger();
        final CoordinationStore counting = intercepting((proxy, method, args) -> {
            final Object result = method.invoke(store, args);
            if (method.getName().equals("awaitChange")) {
                out.incrementAndGet();
                ((CompletionStage<?>) result).whenComplete((ignored, error) -> out.decrementAndGet());
            }
            return result;
        });
        designate(BLUE);
        final CompletableFuture<Admission> parked = new ExclusiveRuns(counting, time).begin(designated(GREEN)
                .enabledBy(KEY)
                .completedWhen("data/euc1")
                .generation(TODAY)
                .handoverWait(Duration.ofHours(4))
                .build()).toCompletableFuture();

        // Each write wakes the park, which looks again and parks again, while the watches on the other
        // keys are still out from the round before.
        assertEquals(4, out.get());
        for (int i = 0; i < 10; i++) {
            designate(i % 2 == 0 ? "apac1-blue" : BLUE);
            assertEquals(4, out.get(), "A park must not pile up watches on keys that did not move");
        }

        assertFalse(parked.isDone());
        parked.cancel(false);
        time.advance(Duration.ofHours(1));
        assertEquals(0, out.get());
    }

    @Test
    void leavesALeaseTakenForAnAskNobodyIsWaitingOnAnyMoreToLapse() {
        final AtomicReference<CompletableFuture<Admission>> asked = new AtomicReference<>();
        final ExclusiveRuns theirRuns = new ExclusiveRuns(
                new RacedAcquireStore(store, () -> asked.get().cancel(false)), time);

        designate(BLUE);
        final CompletableFuture<Admission> parked = theirRuns.begin(designated(GREEN)
                .handoverWait(Duration.ofHours(4))
                .build()).toCompletableFuture();
        asked.set(parked);

        // The designation moves, so the parked candidate wakes and takes the lease -- and the build it
        // is taking it for is cancelled in the moment between asking for it and being given it.
        designate(GREEN);

        assertTrue(parked.isCancelled());
        time.advance(LEASE);
        assertInstanceOf(Admitted.class, join(runs.begin(designated(GREEN, "#2").build())),
                "And it lapses within its term");
    }

    @Test
    void doesNotGiveAwayALeaseARetryAlreadyHolds() {
        final CompletableFuture<Void> answer = new CompletableFuture<>();
        final AtomicInteger acquires = new AtomicInteger();
        final CoordinationStore late = intercepting((proxy, method, args) -> {
            final Object result = method.invoke(store, args);
            if (method.getName().equals("tryAcquire") && acquires.getAndIncrement() == 0) {
                // The first acquire lands in the store, and its answer is slow to come back.
                return answer.thenCompose(ignored -> (CompletionStage<?>) result);
            }
            return result;
        });
        final ExclusiveRuns theirRuns = new ExclusiveRuns(late, time);

        final CompletableFuture<Admission> first = theirRuns.begin(elected(BLUE).build()).toCompletableFuture();
        first.cancel(false);
        // A retry of the same run is told it already holds the lease, and starts the work.
        final Admitted retried = assertInstanceOf(Admitted.class, join(theirRuns.begin(elected(BLUE).build())));

        answer.complete(null);

        assertTrue(retried.isHeld());
        assertInstanceOf(Admission.HeldByOther.class, join(runs.begin(elected(GREEN).build())),
                "The late answer to the cancelled ask must not give the retry's lease away");
        time.advance(LEASE.multipliedBy(3));
        assertTrue(retried.isHeld(), "And the retry goes on renewing it");
    }

    // ---- helpers --------------------------------------------------------------------------------

    @Test
    void answersAStoreThatThrowsWithAFailedStage() {
        final IllegalStateException closed = new IllegalStateException("Closed");
        final CoordinationStore refusing = intercepting((proxy, method, args) -> {
            if (method.getName().equals("get")) {
                throw closed;
            }
            return method.invoke(store, args);
        });

        // Thrown from the first read, which is made before begin() has handed anything back.
        final CompletionStage<Admission> asked =
                new ExclusiveRuns(refusing, time).begin(designated(BLUE).build());

        final CompletionException failed =
                assertThrows(CompletionException.class, () -> join(asked));
        assertEquals(closed, failed.getCause());
    }

    @Test
    void saysNobodyHoldsTheLeaseWhenTakingItLostARace() {
        final CoordinationStore contending = intercepting((proxy, method, args) ->
                method.getName().equals("tryAcquire")
                        ? CompletableFuture.completedFuture(new LeaseAttempt.Contended())
                        : method.invoke(store, args));
        final List<RunRef> told = new ArrayList<>();
        final ExclusiveRuns racing = new ExclusiveRuns(contending, time, new PiplexObserver() {
            @Override
            public void contended(final RunRef run) {
                told.add(run);
            }
        });

        assertInstanceOf(Admission.Contended.class, join(racing.begin(elected(BLUE).build())));
        assertEquals(1, told.size(), "The observer has to hear it as contention: " + told);
    }

    /**
     * The store, except that a background watch never hears of a change and answers only when its
     * wait runs out -- a watch polled far less often than the round.
     *
     * @return the store so changed
     */
    private CoordinationStore blindWhileWaiting() {
        return intercepting((proxy, method, args) -> {
            if (method.getName().equals("awaitChange") && args.length == 4
                    && args[3] == Watch.BACKGROUND) {
                final CompletableFuture<Entry> unchanged = new CompletableFuture<>();
                time.schedule((Duration) args[2],
                        () -> unchanged.complete(Entry.absent((String) args[1])));
                return unchanged;
            }
            return method.invoke(store, args);
        });
    }

    /**
     * The store, except that the first read after a lease is taken lasts a whole lease.
     *
     * @return the store so changed
     */
    private CoordinationStore readsTakeALease() {
        final AtomicInteger acquired = new AtomicInteger();
        final AtomicInteger slowed = new AtomicInteger();
        return intercepting((proxy, method, args) -> {
            if (method.getName().equals("tryAcquire")) {
                acquired.incrementAndGet();
            } else if (method.getName().equals("get") && acquired.get() > 0
                    && slowed.getAndIncrement() == 0) {
                time.advance(LEASE);
            }
            return method.invoke(store, args);
        });
    }

    private CoordinationStore counting(final String operation, final AtomicInteger calls) {
        return intercepting((proxy, method, args) -> {
            if (method.getName().equals(operation)) {
                calls.incrementAndGet();
            }
            return method.invoke(store, args);
        });
    }

    private CoordinationStore intercepting(final InvocationHandler handler) {
        return (CoordinationStore) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {CoordinationStore.class}, handler);
    }

    /**
     * The store, except that a publish to data/euc1 lands only once the gate opens.
     *
     * @param gate what the publish waits for
     * @return the store so changed
     */
    private CoordinationStore publishingAfter(final CompletableFuture<Void> gate) {
        return intercepting((proxy, method, args) -> {
            if (method.getName().equals("compareAndSet") && args[0].equals(Milestones.keyOf("data/euc1"))) {
                return gate.thenCompose(ignored -> store.compareAndSet(
                        (String) args[0], (String) args[1], (String) args[2]));
            }
            return method.invoke(store, args);
        });
    }

    private static List<Revocation> revocations(final Admitted held) {
        final List<Revocation> seen = new ArrayList<>();
        held.onRevoked(seen::add);
        return seen;
    }

    private ExclusiveRequest.Builder elected(final String owner) {
        return elected(owner, "#1");
    }

    private ExclusiveRequest.Builder elected(final String owner, final String run) {
        return ExclusiveRequest.builder(KEY).ownedBy(owner).runId(owner + run).lease(LEASE);
    }

    private ExclusiveRequest.Builder completing(final String owner) {
        return elected(owner).generation(TODAY).completedWhen("data/euc1");
    }

    private ExclusiveRequest.Builder active(final String owner) {
        return elected(owner).activeWhen(ACTIVE, owner);
    }

    private ExclusiveRequest.Builder designated(final String owner) {
        return elected(owner).designatedBy(KEY);
    }

    private ExclusiveRequest.Builder designated(final String owner, final String run) {
        return elected(owner, run).designatedBy(KEY);
    }

    private ExclusiveRequest request(final Duration lease) {
        return elected(BLUE).lease(lease).build();
    }

    private void designate(final String owner) {
        join(new Designations(store, time).designate(KEY, owner, "handover"));
    }

    private void put(final String key, final String value) {
        assertTrue(join(store.compareAndSet(key, CoordinationStore.INITIAL_VERSION, value)));
    }

    // What deleting a key looks like to a store which keeps a tombstone and a version for it.
    private void remove(final String key) {
        assertTrue(join(store.compareAndSet(key, join(store.get(key)).version(), null)));
    }

    private void overwrite(final String key, final String value) {
        assertTrue(join(store.compareAndSet(key, join(store.get(key)).version(), value)));
    }

    private static <T> T join(final CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    // Anything locked while a callback runs keeps this from finishing.
    private static void onAnotherThread(final Runnable action) {
        try {
            CompletableFuture.runAsync(action).get(30, TimeUnit.SECONDS);
        } catch (final TimeoutException blocked) {
            throw new AssertionError("A callback must be told with nothing locked", blocked);
        } catch (final InterruptedException | ExecutionException failed) {
            throw new AssertionError(failed);
        }
    }

    record Outage(String name, Consumer<UnreachableStore> breaks) {

        @Override
        public String toString() {
            return name;
        }
    }

    record RenewalOutage(String name,
                         Consumer<UnreachableStore> breaks,
                         Duration grace,
                         Duration heldAt,
                         Duration goneAt) {

        @Override
        public String toString() {
            return name;
        }
    }
}
