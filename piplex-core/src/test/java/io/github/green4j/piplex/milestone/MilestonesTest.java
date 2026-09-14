/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.milestone;

import io.github.green4j.piplex.Generation;
import io.github.green4j.piplex.ManualTime;
import io.github.green4j.piplex.store.memory.InMemoryCoordinationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    private Milestones milestones;

    @BeforeEach
    void setUp() {
        time = new ManualTime();
        milestones = new Milestones(new InMemoryCoordinationStore(time), time);
    }

    @Test
    void publishesTheFirstGeneration() {
        final PublishResult result = join(milestones.publish(KEY, D2, "euc1-blue", "eod#142"));
        assertEquals(PublishResult.Outcome.PUBLISHED, result.outcome());
        assertEquals(D2, result.inForce().generation());
        assertEquals("euc1-blue", result.inForce().by());
        assertEquals("eod#142", result.inForce().runId());
    }

    @Test
    void publishingTheSameGenerationAgainChangesNothing() {
        join(milestones.publish(KEY, D2, "euc1-blue", "eod#142"));
        final PublishResult again = join(milestones.publish(KEY, D2, "euc1-blue", "eod#143"));
        assertEquals(PublishResult.Outcome.ALREADY_AT_OR_AHEAD, again.outcome());
        // the record is the original one: a re-run must not rewrite who got there first
        assertEquals("eod#142", join(milestones.current(KEY)).runId());
    }

    @Test
    void neverMovesAMilestoneBackwards() {
        join(milestones.publish(KEY, D2, "euc1-blue", "eod#142"));
        final PublishResult older = join(milestones.publish(KEY, D1, "euc1-blue", "eod#99"));
        assertEquals(PublishResult.Outcome.ALREADY_AT_OR_AHEAD, older.outcome());
        assertEquals(D2, join(milestones.current(KEY)).generation());
    }

    @Test
    void movesForward() {
        join(milestones.publish(KEY, D2, "euc1-blue", "eod#142"));
        assertEquals(PublishResult.Outcome.PUBLISHED,
                join(milestones.publish(KEY, D3, "euc1-blue", "eod#150")).outcome());
        assertEquals(D3, join(milestones.current(KEY)).generation());
    }

    @Test
    void returnsAtOnceWhenTheMilestoneIsAlreadyThere() {
        join(milestones.publish(KEY, D2, "euc1-blue", "eod#142"));
        final AwaitResult waited = join(milestones.awaitAtLeast(KEY, D2, TIMEOUT));
        assertEquals(AwaitResult.Outcome.REACHED, waited.outcome());
        assertEquals(0, time.pending(), "a wait satisfied on the first read must schedule nothing");
    }

    @Test
    void returnsAtOnceWhenTheMilestoneIsAlreadyPastIt() {
        join(milestones.publish(KEY, D3, "euc1-blue", "eod#150"));
        assertEquals(AwaitResult.Outcome.REACHED, join(milestones.awaitAtLeast(KEY, D2, TIMEOUT)).outcome());
    }

    @Test
    void unblocksWhenTheProducerPublishes() {
        final CompletableFuture<AwaitResult> waited =
                milestones.awaitAtLeast(KEY, D2, TIMEOUT).toCompletableFuture();
        assertFalse(waited.isDone(), "nothing has been published yet");

        join(milestones.publish(KEY, D2, "euc1-blue", "eod#142"));

        assertTrue(waited.isDone());
        assertEquals(AwaitResult.Outcome.REACHED, waited.join().outcome());
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
    void timesOutRatherThanWaitingForever() {
        final CompletableFuture<AwaitResult> waited =
                milestones.awaitAtLeast(KEY, D2, TIMEOUT).toCompletableFuture();
        assertFalse(waited.isDone());

        time.advance(TIMEOUT);

        assertTrue(waited.isDone());
        final AwaitResult result = waited.join();
        assertEquals(AwaitResult.Outcome.TIMED_OUT, result.outcome());
        assertNull(result.inForce(), "nothing was ever published");
    }

    @Test
    void reportsWhatWasInForceWhenItTimedOut() {
        join(milestones.publish(KEY, D1, "euc1-blue", "eod#99"));
        final CompletableFuture<AwaitResult> waited =
                milestones.awaitAtLeast(KEY, D3, TIMEOUT).toCompletableFuture();

        time.advance(TIMEOUT);

        final AwaitResult result = waited.join();
        assertEquals(AwaitResult.Outcome.TIMED_OUT, result.outcome());
        assertEquals(D1, result.inForce().generation(), "an operator needs to see how far it did get");
    }

    @Test
    void hasNoMilestoneUntilOneIsPublished() {
        assertNull(join(milestones.current(KEY)));
    }

    @Test
    void survivesARoundTripThroughJson() {
        join(milestones.publish(KEY, D2, "euc1-blue", "eod#142"));
        final Milestone read = join(milestones.current(KEY));
        assertNotNull(read.at());
        assertEquals(read, Milestone.parse(read.toJson()));
    }

    @Test
    void refusesWhatIsNotARecordAtAll() {
        // The shapes a key ends up holding once somebody edits it by hand. The text goes in the
        // message because it is the only copy of what the key held.
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Milestone.parse("{\"generation\""))
                .getMessage().contains("{\"generation\""));
        assertThrows(IllegalArgumentException.class, () -> Milestone.parse("[1,2]"));
        assertThrows(IllegalArgumentException.class, () -> Milestone.parse(""));
    }

    private static <T> T join(final CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
