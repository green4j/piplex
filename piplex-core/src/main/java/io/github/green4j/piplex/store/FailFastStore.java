/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.store;

import io.github.green4j.piplex.TimeSource;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * A store whose every failure arrives as a failed stage, and within a bounded time.
 *
 * <p>A store may refuse a call outright -- a closed client usually does -- and a throw where a stage was
 * expected skips every handler written for the stage. A stage still pending past
 * {@link CoordinationStore#responseBound()} -- plus the wait, for {@link #awaitChange} -- fails with a
 * {@link TimeoutException}, which says what any failure says: the outcome was not learnt. The store's
 * own stage is left alone, never cancelled. The primitives wrap the store they are given in this, so
 * they handle one kind of failure instead of two, and never wait on a store for good.
 */
public final class FailFastStore implements CoordinationStore {

    private final CoordinationStore delegate;
    private final TimeSource time;
    private final Duration bound;

    private FailFastStore(final CoordinationStore delegate, final TimeSource time) {
        this.delegate = delegate;
        this.time = time;
        this.bound = Objects.requireNonNull(delegate.responseBound(), "responseBound");
        if (bound.isNegative() || bound.isZero()) {
            throw new IllegalArgumentException("responseBound must be positive, but got " + bound);
        }
    }

    /**
     * @param store the store to wrap
     * @param time  what times its answers
     * @return the store, wrapped once
     */
    public static CoordinationStore of(final CoordinationStore store, final TimeSource time) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(time, "time");
        return store instanceof FailFastStore ? store : new FailFastStore(store, time);
    }

    @Override
    public CompletionStage<Entry> get(final String key) {
        return called("get", key, Duration.ZERO, () -> delegate.get(key));
    }

    @Override
    public CompletionStage<Boolean> compareAndSet(final String key,
                                                  final String expectedVersion,
                                                  final String value) {
        return called("compareAndSet", key, Duration.ZERO,
                () -> delegate.compareAndSet(key, expectedVersion, value));
    }

    @Override
    public CompletionStage<Entry> awaitChange(final String key,
                                              final String sinceVersion,
                                              final Duration maxWait) {
        return called("awaitChange", key, maxWait, () -> delegate.awaitChange(key, sinceVersion, maxWait));
    }

    @Override
    public CompletionStage<Entry> awaitChange(final String key,
                                              final String sinceVersion,
                                              final Duration maxWait,
                                              final Watch watch) {
        return called("awaitChange", key, maxWait,
                () -> delegate.awaitChange(key, sinceVersion, maxWait, watch));
    }

    @Override
    public CompletionStage<LeaseAttempt> tryAcquire(final String key,
                                                    final String ownerId,
                                                    final Duration ttl) {
        return called("tryAcquire", key, Duration.ZERO, () -> delegate.tryAcquire(key, ownerId, ttl));
    }

    @Override
    public CompletionStage<Boolean> renew(final String key, final LeaseHandle handle, final Duration ttl) {
        return called("renew", key, Duration.ZERO, () -> delegate.renew(key, handle, ttl));
    }

    @Override
    public CompletionStage<Void> release(final String key, final LeaseHandle handle) {
        return called("release", key, Duration.ZERO, () -> delegate.release(key, handle));
    }

    @Override
    public Duration responseBound() {
        return bound;
    }

    @Override
    public void close() {
        delegate.close();
    }

    private <T> CompletionStage<T> called(final String operation,
                                          final String key,
                                          final Duration wait,
                                          final Supplier<CompletionStage<T>> call) {
        final Duration limit = bound.plus(wait == null || wait.isNegative() ? Duration.ZERO : wait);
        final CompletableFuture<T> answer = new CompletableFuture<>();
        // Armed before the call, so a scheduler which refuses -- one shutting down -- fails the call
        // before anything was sent, rather than after with the outcome unknown.
        final TimeSource.Cancellable timer;
        final CompletionStage<T> stage;
        try {
            timer = time.schedule(limit, () -> answer.completeExceptionally(new TimeoutException(
                    "The store did not answer " + operation + " on '" + key + "' within " + limit)));
        } catch (final RuntimeException refused) {
            return CompletableFuture.failedFuture(refused);
        }
        try {
            stage = call.get();
        } catch (final RuntimeException refused) {
            timer.cancel();
            return CompletableFuture.failedFuture(refused);
        }
        if (stage == null) {
            timer.cancel();
            return CompletableFuture.failedFuture(new IllegalStateException("The store returned no stage"));
        }
        stage.whenComplete((value, error) -> {
            timer.cancel();
            if (error != null) {
                answer.completeExceptionally(error);
            } else {
                answer.complete(value);
            }
        });
        return answer;
    }
}
