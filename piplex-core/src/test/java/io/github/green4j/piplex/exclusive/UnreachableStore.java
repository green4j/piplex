/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.exclusive;

import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.Entry;
import io.github.green4j.piplex.store.LeaseAttempt;
import io.github.green4j.piplex.store.LeaseHandle;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * A store which can be told to stop answering, one call at a time, so that the awkward cases -- a lease
 * which cannot be extended, a watch which cannot reach the key it is watching -- can be tested without a
 * network. The two are separate because a store which answers one and not the other is the ordinary
 * shape of the trouble: a watch is a long poll and fails on its own schedule.
 *
 * <p>Constructed explicitly around another store, like every implementation here.
 */
final class UnreachableStore implements CoordinationStore {

    private final CoordinationStore delegate;
    private volatile boolean renews = true;
    private volatile boolean watches = true;

    UnreachableStore(final CoordinationStore delegate) {
        this.delegate = delegate;
    }

    void stopAnsweringRenewals() {
        renews = false;
    }

    void answerRenewalsAgain() {
        renews = true;
    }

    void stopAnsweringWatches() {
        watches = false;
    }

    void answerWatchesAgain() {
        watches = true;
    }

    @Override
    public CompletionStage<Boolean> renew(final String key, final LeaseHandle handle, final Duration ttl) {
        if (!renews) {
            return CompletableFuture.failedFuture(new IOException("no route to the cluster"));
        }
        return delegate.renew(key, handle, ttl);
    }

    @Override
    public CompletionStage<Entry> get(final String key) {
        return delegate.get(key);
    }

    @Override
    public CompletionStage<Boolean> compareAndSet(final String key,
                                                  final String expectedVersion,
                                                  final String value) {
        return delegate.compareAndSet(key, expectedVersion, value);
    }

    @Override
    public CompletionStage<Entry> awaitChange(final String key,
                                              final String sinceVersion,
                                              final Duration maxWait) {
        if (!watches) {
            return CompletableFuture.failedFuture(new IOException("no route to the cluster"));
        }
        return delegate.awaitChange(key, sinceVersion, maxWait);
    }

    @Override
    public CompletionStage<LeaseAttempt> tryAcquire(final String key,
                                                    final String ownerId,
                                                    final Duration ttl) {
        return delegate.tryAcquire(key, ownerId, ttl);
    }

    @Override
    public CompletionStage<Void> release(final String key, final LeaseHandle handle) {
        return delegate.release(key, handle);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
