/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.milestone;

import io.github.green4j.piplex.Environment;
import io.github.green4j.piplex.Generation;
import io.github.green4j.piplex.ManualTime;
import io.github.green4j.piplex.SilentStore;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.memory.InMemoryCoordinationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MilestonesTest {

    private static final String KEY = "data/euc1";
    private static final Generation D1 = Generation.of("2026-09-10");
    private static final Generation D2 = Generation.of("2026-09-11");
    private static final Generation D3 = Generation.of("2026-09-12");
    private static final Duration TIMEOUT = Duration.ofMinutes(90);

    private ManualTime time;
    private InMemoryCoordinationStore store;
    private Milestones milestones;

    @BeforeEach
    void setUp() {
        time = new ManualTime();
        store = new InMemoryCoordinationStore(time);
        milestones = new Milestones(store, time);
    }

    @ParameterizedTest
    @CsvSource({
        ",           2026-09-11, PUBLISHED,           2026-09-11, eod#2",
        "2026-09-11, 2026-09-12, PUBLISHED,           2026-09-12, eod#2",
        // A re-run must not rewrite who got there first.
        "2026-09-11, 2026-09-11, ALREADY_AT_OR_AHEAD, 2026-09-11, eod#1",
        "2026-09-12, 2026-09-11, ALREADY_AT_OR_AHEAD, 2026-09-12, eod#1",
    })
    void onlyEverMovesAMilestoneForward(final String before,
                                        final String published,
                                        final PublishResult.Outcome outcome,
                                        final String inForce,
                                        final String runId) {
        if (before != null) {
            join(milestones.publish(KEY, Generation.of(before), "euc1-blue", "eod#1"));
        }

        final PublishResult result = join(milestones.publish(KEY, Generation.of(published), "euc1-blue", "eod#2"));

        assertEquals(outcome, result.outcome());
        final Milestone now = join(milestones.current(KEY));
        assertEquals(now, result.inForce());
        assertEquals(new Milestone(Generation.of(inForce), "euc1-blue", runId, now.at()), now);
    }

    @Test
    void readsARecordWhoseStampIsMalformed() {
        assertEquals(new Milestone(D2, "euc1-blue", null, null),
                Milestone.parse("{\"generation\":\"" + D2.value() + "\",\"by\":\"euc1-blue\",\"at\":\"today\"}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-09-11", "2026-09-12"})
    void returnsAtOnceWhenTheMilestoneIsAlreadyThere(final String reached) {
        join(milestones.publish(KEY, Generation.of(reached), "euc1-blue", "eod#142"));
        final AwaitResult waited = join(milestones.awaitAtLeast(KEY, D2, TIMEOUT));
        assertEquals(AwaitResult.Outcome.REACHED, waited.outcome());
        assertEquals(0, time.pending(), "A wait satisfied on the first read must schedule nothing");
    }

    @Test
    void keepsWaitingWhileTheGenerationIsStillTooOld() {
        join(milestones.publish(KEY, D1, "euc1-blue", "eod#99"));

        final CompletableFuture<AwaitResult> waited =
                milestones.awaitAtLeast(KEY, D3, TIMEOUT).toCompletableFuture();
        assertFalse(waited.isDone());

        join(milestones.publish(KEY, D2, "euc1-blue", "eod#142"));
        assertFalse(waited.isDone(), "D2 is a change, but not the generation asked for");

        join(milestones.publish(KEY, D3, "euc1-blue", "eod#150"));
        assertTrue(waited.isDone());
        assertEquals(D3, waited.join().inForce().generation());
    }

    @Test
    void stopsWaitingWhenNobodyIsWaitingForTheAnswerAnyMore() {
        final CompletableFuture<AwaitResult> waited =
                milestones.awaitAtLeast(KEY, D2, TIMEOUT).toCompletableFuture();

        // The build was stopped. The watch in flight cannot be called off, but nothing may follow it.
        waited.cancel(false);
        time.advance(Milestones.ROUND);

        assertEquals(0, time.pending(), "An abandoned wait must leave nothing behind it");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "2026-09-10")
    void timesOutRatherThanWaitingForeverAndSaysHowFarItGot(final String reached) {
        if (reached != null) {
            join(milestones.publish(KEY, Generation.of(reached), "euc1-blue", "eod#99"));
        }
        final CompletableFuture<AwaitResult> waited =
                milestones.awaitAtLeast(KEY, D3, TIMEOUT).toCompletableFuture();
        assertFalse(waited.isDone());

        time.advance(TIMEOUT);

        assertTrue(waited.isDone());
        final AwaitResult result = waited.join();
        assertEquals(AwaitResult.Outcome.TIMED_OUT, result.outcome());
        assertEquals(reached, result.inForce() == null ? null : result.inForce().generation().value(),
                "An operator needs to see how far it did get");
    }

    @Test
    void endsAWaitWhoseReadTheStoreNeverAnswers() {
        final CompletableFuture<AwaitResult> waited = new Milestones(
                new SilentStore(CoordinationStore.DEFAULT_RESPONSE_BOUND).store(), time)
                .awaitAtLeast(KEY, D2, Duration.ofMinutes(1)).toCompletableFuture();

        time.advance(CoordinationStore.DEFAULT_RESPONSE_BOUND);

        assertTrue(waited.isDone(), "A read which never comes back must not outlast the bound");
        final CompletionException thrown = assertThrows(CompletionException.class, waited::join);
        assertInstanceOf(TimeoutException.class, thrown.getCause());
    }

    @Test
    void repairsAMilestoneThatWillNotParse() {
        join(store.compareAndSet(
                Milestones.keyOf(Environment.DEFAULT, KEY), CoordinationStore.INITIAL_VERSION, "{\"generation\""));

        assertEquals(PublishResult.Outcome.PUBLISHED, join(milestones.repair(KEY, D2)).outcome());
        assertEquals(D2, join(milestones.current(KEY)).generation());
        assertEquals(PublishResult.Outcome.ALREADY_AT_OR_AHEAD,
                join(milestones.repair(KEY, D1)).outcome(), "Readable, it must not move backwards");
    }

    @Test
    void refusesToPublishOrWaitForNoGeneration() {
        // Refused at the call, like a designation naming nobody, and not as a failed stage from
        // somewhere inside the write.
        assertThrows(NullPointerException.class, () -> milestones.publish(KEY, null, "euc1-blue", "eod#142"));
        assertThrows(NullPointerException.class, () -> milestones.repair(KEY, null));
        assertThrows(NullPointerException.class, () -> milestones.awaitAtLeast(KEY, null, Duration.ofMinutes(1L)));
        assertThrows(NullPointerException.class, () -> milestones.awaitAtLeast(KEY, D2, null));
        assertNull(join(milestones.current(KEY)), "And nothing reached the store");
    }

    private static <T> T join(final CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
