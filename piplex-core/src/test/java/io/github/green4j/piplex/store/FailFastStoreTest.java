/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.store;

import io.github.green4j.piplex.ManualTime;
import io.github.green4j.piplex.SilentStore;
import io.github.green4j.piplex.TimeSource;
import io.github.green4j.piplex.store.memory.InMemoryCoordinationStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailFastStoreTest {

    private static final Duration BOUND = Duration.ofSeconds(30);
    private static final Duration JUST = Duration.ofMillis(1);

    private final ManualTime time = new ManualTime();

    @Test
    void failsAStageTheStoreNeverAnswersOnceItsBoundIsOut() {
        final SilentStore silent = new SilentStore(BOUND);
        final CompletableFuture<Entry> read = FailFastStore.of(silent.store(), time)
                .get("k").toCompletableFuture();

        time.advance(BOUND.minus(JUST));
        assertFalse(read.isDone());
        time.advance(JUST);

        assertTrue(read.isDone());
        final CompletionException thrown = assertThrows(CompletionException.class, read::join);
        assertInstanceOf(TimeoutException.class, thrown.getCause());
        assertFalse(silent.taken().get(0).isCancelled(), "The store's own stage is not piplex's to cancel");
    }

    @Test
    void givesAWaitItsOwnLengthOnTopOfTheBound() {
        final SilentStore silent = new SilentStore(BOUND);
        final Duration wait = Duration.ofMinutes(1);
        final CompletableFuture<Entry> watched = FailFastStore.of(silent.store(), time)
                .awaitChange("k", CoordinationStore.INITIAL_VERSION, wait, Watch.BACKGROUND)
                .toCompletableFuture();

        time.advance(BOUND.plus(wait).minus(JUST));
        assertFalse(watched.isDone());
        time.advance(JUST);

        assertTrue(watched.isCompletedExceptionally());
    }

    @Test
    void leavesNoTimerBehindOnceTheStoreAnswers() {
        final CoordinationStore store = FailFastStore.of(new InMemoryCoordinationStore(time), time);

        assertTrue(store.compareAndSet("k", CoordinationStore.INITIAL_VERSION, "v").toCompletableFuture().join());
        assertEquals("v", store.get("k").toCompletableFuture().join().value());
        assertEquals(0, time.pending());
    }

    @Test
    void sendsNothingWhenTheSchedulerRefusesTheTimer() {
        final SilentStore silent = new SilentStore(BOUND);
        final TimeSource refusing = new TimeSource() {
            @Override
            public long nanos() {
                return time.nanos();
            }

            @Override
            public Instant wallTime() {
                return time.wallTime();
            }

            @Override
            public Cancellable schedule(final Duration delay, final Runnable action) {
                throw new RejectedExecutionException("The scheduler has been shut down");
            }
        };

        final CompletableFuture<Entry> read = FailFastStore.of(silent.store(), refusing)
                .get("k").toCompletableFuture();

        final CompletionException thrown = assertThrows(CompletionException.class, read::join);
        assertInstanceOf(RejectedExecutionException.class, thrown.getCause());
        assertTrue(silent.taken().isEmpty(), "A call nobody can time out is not made");
    }

    @Test
    void refusesAStoreWhichDeclaresNoBound() {
        assertThrows(IllegalArgumentException.class,
                () -> FailFastStore.of(new SilentStore(Duration.ZERO).store(), time));
    }
}
