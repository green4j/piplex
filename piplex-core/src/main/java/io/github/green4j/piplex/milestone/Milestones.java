/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.milestone;

import io.github.green4j.piplex.Environment;
import io.github.green4j.piplex.Generation;
import io.github.green4j.piplex.UnreadableKeyException;
import io.github.green4j.piplex.observe.PiplexObserver;
import io.github.green4j.piplex.observe.RunRef;
import io.github.green4j.piplex.store.CompareAndSetLoop;
import io.github.green4j.piplex.store.CompareAndSetLoop.Step;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.FailFastStore;
import io.github.green4j.piplex.store.Watch;
import io.github.green4j.piplex.store.Entry;

import io.github.green4j.piplex.TimeSource;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Publishes completed generations and waits until a milestone reaches one.
 *
 * <p>Publication is monotonic and idempotent. Waiting repeatedly compares current state rather than
 * counting changes, so coalesced watches and restarts do not lose progress.
 */
public final class Milestones {

    private static final String KIND = "milestone";
    // The longest one watch is given. A watch cannot be called off, so this is how long a wait that
    // nobody is waiting on any more can go on.
    static final Duration ROUND = Duration.ofMinutes(1);

    private final CoordinationStore store;
    private final TimeSource time;
    private final PiplexObserver observer;
    private final Environment environment;
    private final String prefix;

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
        this(store, time, observer, Environment.DEFAULT);
    }

    /**
     * @param store       where milestones are kept
     * @param time        where time comes from
     * @param observer    told when a milestone moves or a wait ends
     * @param environment which set of orchestrations these milestones belong to
     */
    public Milestones(final CoordinationStore store,
                      final TimeSource time,
                      final PiplexObserver observer,
                      final Environment environment) {
        this.store = FailFastStore.of(store, time);
        this.time = Objects.requireNonNull(time, "time");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.prefix = environment.prefixOf(KIND);
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
        Objects.requireNonNull(generation, "generation");
        return publish(key, generation, by, runId, false);
    }

    /**
     * Publishes as {@link #publish} does, and also replaces a value that does not parse.
     *
     * <p>An operator's call, never a producer's: what the replaced value said is unknown, so the
     * generation given here may move the milestone backwards. The record it writes names no run; who
     * repaired it is in the log of the job that did.
     *
     * @param key        the milestone
     * @param generation how far the work is known to have got
     * @return what happened
     */
    public CompletionStage<PublishResult> repair(final String key, final Generation generation) {
        Objects.requireNonNull(generation, "generation");
        return publish(key, generation, null, null, true);
    }

    /**
     * Waits until a milestone reaches at least the generation given.
     *
     * <p>Cancel what this returns and the wait ends within one {@link #ROUND}.
     *
     * @param key        the milestone
     * @param generation the generation needed
     * @param timeout    how long to wait for it
     * @return whether it was reached, and what was in force when the wait ended
     */
    public CompletionStage<AwaitResult> awaitAtLeast(final String key,
                                                     final Generation generation,
                                                     final Duration timeout) {
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(timeout, "timeout");
        final CompletableFuture<AwaitResult> answer = new CompletableFuture<>();
        await(key, generation, time.deadlineIn(timeout), timeout, answer)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        answer.completeExceptionally(error);
                    } else {
                        answer.complete(result);
                    }
                });
        return answer;
    }

    /**
     * Reads a milestone without waiting.
     *
     * @param key the milestone
     * @return the record held, or {@code null} when the key holds nothing yet
     */
    public CompletionStage<Milestone> current(final String key) {
        final String storeKey = storeKeyOf(key);
        return store.get(storeKey).thenApply(entry -> milestoneOf(storeKey, entry));
    }

    /**
     * Where a milestone is kept, for an operator reading the store by hand.
     *
     * @param environment which set of orchestrations it belongs to
     * @param key         the milestone
     * @return the key it is held at
     * @throws IllegalArgumentException if the key is null or blank
     */
    public static String keyOf(final Environment environment, final String key) {
        Objects.requireNonNull(environment, "environment");
        return environment.prefixOf(KIND) + checked(key);
    }

    /**
     * Every way into this class goes through this check: a blank key does not fail, it names the
     * prefix itself, and then every milestone in the environment is one.
     *
     * @param key the milestone
     * @return it, once it is worth composing a key from
     * @throws IllegalArgumentException if the key is null or blank
     */
    private static String checked(final String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        return key;
    }

    private String storeKeyOf(final String key) {
        return prefix + checked(key);
    }

    private CompletionStage<PublishResult> publish(final String key,
                                                   final Generation generation,
                                                   final String by,
                                                   final String runId,
                                                   final boolean overwriteUnreadable) {
        final String storeKey = storeKeyOf(key);
        return CompareAndSetLoop.write(store, storeKey, entry -> {
            Milestone inForce = null;
            try {
                inForce = milestoneOf(storeKey, entry);
            } catch (final UnreadableKeyException notReadable) {
                if (!overwriteUnreadable) {
                    // Said in the run's own log as well as thrown: a producer whose milestone key was
                    // hand-edited is fixed by an operator reading that log, not the stack trace.
                    observer.guardUnreadable(
                            new RunRef(environment, key, generation, by, runId),
                            storeKey,
                            notReadable.getMessage());
                    throw notReadable;
                }
            }
            if (inForce != null && inForce.generation().atLeast(generation)) {
                return Step.keep(new PublishResult(PublishResult.Outcome.ALREADY_AT_OR_AHEAD, inForce));
            }
            final Milestone next = new Milestone(generation, by, runId, time.wallTime());
            return Step.write(next.toJson(), () -> {
                observer.milestonePublished(new RunRef(environment, key, generation, by, runId));
                return new PublishResult(PublishResult.Outcome.PUBLISHED, next);
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
     * @param answer        what the caller is waiting on; once it is done, nobody is
     * @return whether it was reached
     */
    private CompletionStage<AwaitResult> await(final String key,
                                               final Generation generation,
                                               final long deadlineNanos,
                                               final Duration announce,
                                               final CompletableFuture<AwaitResult> answer) {
        final String storeKey = storeKeyOf(key);
        if (answer.isDone()) {
            return CompletableFuture.failedFuture(
                    new CancellationException("The wait for '" + key + "' was abandoned"));
        }
        return store.get(storeKey).thenCompose(entry -> {
            final Milestone inForce = milestoneOf(storeKey, entry);
            if (inForce != null && inForce.generation().atLeast(generation)) {
                observer.milestoneReached(environment, key, inForce.generation());
                return CompletableFuture.completedFuture(
                        new AwaitResult(AwaitResult.Outcome.REACHED, inForce));
            }
            if (announce != null) {
                observer.milestoneWaiting(environment, key, generation,
                        inForce == null ? null : inForce.generation(), announce);
            }
            final Duration remaining = time.until(deadlineNanos);
            if (remaining.isZero()) {
                observer.milestoneTimedOut(environment, key, generation,
                        inForce == null ? null : inForce.generation());
                return CompletableFuture.completedFuture(
                        new AwaitResult(AwaitResult.Outcome.TIMED_OUT, inForce));
            }
            // Whether the answer was confirmed is deliberately not asked: the wait is only how this
            // loop is paced, and every turn of it re-reads at the top. A waiter which failed on an
            // unconfirmed answer would start failing on a partial outage it would otherwise sit out.
            return store.awaitChange(storeKey, entry.version(),
                            remaining.compareTo(ROUND) > 0 ? ROUND : remaining, Watch.BACKGROUND)
                    .thenCompose(ignored -> await(key, generation, deadlineNanos, null, answer));
        });
    }

    private static Milestone milestoneOf(final String storeKey, final Entry entry) {
        if (!entry.exists()) {
            return null;
        }
        try {
            return Milestone.parse(entry.value());
        } catch (final RuntimeException notReadable) {
            throw new UnreadableKeyException(storeKey, "milestone", notReadable);
        }
    }
}
