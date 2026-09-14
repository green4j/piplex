/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.milestone;

import io.github.green4j.piplex.ContendedException;
import io.github.green4j.piplex.Generation;
import io.github.green4j.piplex.observe.PiplexObserver;
import io.github.green4j.piplex.observe.RunRef;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.Entry;

import io.github.green4j.piplex.TimeSource;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * "How far did the producer get", and "wait until it got far enough".
 *
 * <p>This is what makes a pipeline on one controller start because a pipeline on another finished --
 * without either of them knowing the other exists, and without a schedule guessing how long the first
 * one takes.
 *
 * <p>Two properties carry the design:
 *
 * <ul>
 *   <li><b>Publishing is monotonic and idempotent.</b> A milestone never moves backwards, so re-running
 *       a producer for an earlier generation is a no-op rather than a regression, and re-running it for
 *       the same generation costs one read.</li>
 *   <li><b>Waiting compares state, not events.</b> It reads, and only then waits for a change, and then
 *       reads again. That is not a stylistic choice: the store coalesces, so a waiter which counted
 *       events would miss the one it needed. Comparing state is also what makes a restarted waiter
 *       correct -- it holds no position of its own to lose.</li>
 * </ul>
 *
 * <p>Because a waiter supplies the generation it needs, nothing has to remember "the last generation I
 * acted on". That is the whole reason no consumer-side state exists here.
 */
public final class Milestones {

    private static final String PREFIX = "piplex/milestone/";
    private static final int PUBLISH_ATTEMPTS = 8;

    private final CoordinationStore store;
    private final TimeSource time;
    private final PiplexObserver observer;

    /**
     * @param store where milestones are kept
     * @param time  where time comes from
     */
    public Milestones(final CoordinationStore store, final TimeSource time) {
        this(store, time, PiplexObserver.NONE);
    }

    /**
     * @param store    where milestones are kept
     * @param time     where time comes from
     * @param observer told when a milestone moves or a wait ends
     */
    public Milestones(final CoordinationStore store,
                      final TimeSource time,
                      final PiplexObserver observer) {
        this.store = Objects.requireNonNull(store, "store");
        this.time = Objects.requireNonNull(time, "time");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    /**
     * Records that the work for a generation is done.
     *
     * <p>Call it on success and only on success. A producer stopped half way must leave the milestone
     * where it was, so that waiters keep waiting rather than proceed on a partial result.
     *
     * @param key        the milestone
     * @param generation how far the producer got
     * @param by         which owner published it, may be {@code null}
     * @param runId      which run published it, may be {@code null}
     * @return what happened
     */
    public CompletionStage<PublishResult> publish(final String key,
                                                  final Generation generation,
                                                  final String by,
                                                  final String runId) {
        return publish(key, generation, by, runId, PUBLISH_ATTEMPTS);
    }

    /**
     * Waits until a milestone reaches at least the generation given.
     *
     * @param key        the milestone
     * @param generation the generation needed
     * @param timeout    how long to wait for it
     * @return whether it was reached, and what was in force when the wait ended
     */
    public CompletionStage<AwaitResult> awaitAtLeast(final String key,
                                                     final Generation generation,
                                                     final Duration timeout) {
        return await(key, generation, time.deadlineIn(timeout), timeout);
    }

    /**
     * Reads a milestone without waiting.
     *
     * @param key the milestone
     * @return the record held, or {@code null} when the key holds nothing yet
     */
    public CompletionStage<Milestone> current(final String key) {
        return store.get(keyOf(key)).thenApply(Milestones::milestoneOf);
    }

    /**
     * Where a milestone is kept, for an operator reading the store by hand.
     *
     * @param key the milestone
     * @return the key it is held at
     */
    public static String keyOf(final String key) {
        return PREFIX + key;
    }

    private CompletionStage<PublishResult> publish(final String key,
                                                   final Generation generation,
                                                   final String by,
                                                   final String runId,
                                                   final int attemptsLeft) {
        final String storeKey = keyOf(key);
        return store.get(storeKey).thenCompose(entry -> {
            final Milestone inForce = milestoneOf(entry);
            if (inForce != null && inForce.generation().atLeast(generation)) {
                return CompletableFuture.completedFuture(
                        new PublishResult(PublishResult.Outcome.ALREADY_AT_OR_AHEAD, inForce));
            }
            final Milestone next = new Milestone(generation, by, runId, time.wallTime());
            return store.compareAndSet(storeKey, entry.version(), next.toJson()).thenCompose(applied -> {
                if (Boolean.TRUE.equals(applied)) {
                    observer.milestonePublished(new RunRef(key, generation, by, runId));
                    return CompletableFuture.completedFuture(
                            new PublishResult(PublishResult.Outcome.PUBLISHED, next));
                }
                if (attemptsLeft <= 1) {
                    return CompletableFuture.failedFuture(
                            new ContendedException(keyOf(key), PUBLISH_ATTEMPTS));
                }
                return publish(key, generation, by, runId, attemptsLeft - 1);
            });
        });
    }

    /**
     * One look, and a wait for the next one.
     *
     * @param key           the milestone
     * @param generation    the generation needed
     * @param deadlineNanos when to give up
     * @param announce      how long the wait may last, on the first look only; {@code null} afterwards.
     *                      It is what makes the observer hear "waiting" once rather than every look.
     * @return whether it was reached
     */
    private CompletionStage<AwaitResult> await(final String key,
                                               final Generation generation,
                                               final long deadlineNanos,
                                               final Duration announce) {
        final String storeKey = keyOf(key);
        return store.get(storeKey).thenCompose(entry -> {
            final Milestone inForce = milestoneOf(entry);
            if (inForce != null && inForce.generation().atLeast(generation)) {
                observer.milestoneReached(key, inForce.generation());
                return CompletableFuture.completedFuture(
                        new AwaitResult(AwaitResult.Outcome.REACHED, inForce));
            }
            if (announce != null) {
                observer.milestoneWaiting(key, generation,
                        inForce == null ? null : inForce.generation(), announce);
            }
            final Duration remaining = time.until(deadlineNanos);
            if (remaining.isZero()) {
                observer.milestoneTimedOut(key, generation,
                        inForce == null ? null : inForce.generation());
                return CompletableFuture.completedFuture(
                        new AwaitResult(AwaitResult.Outcome.TIMED_OUT, inForce));
            }
            return store.awaitChange(storeKey, entry.version(), remaining)
                    .thenCompose(ignored -> await(key, generation, deadlineNanos, null));
        });
    }

    private static Milestone milestoneOf(final Entry entry) {
        return entry.exists() ? Milestone.parse(entry.value()) : null;
    }
}
