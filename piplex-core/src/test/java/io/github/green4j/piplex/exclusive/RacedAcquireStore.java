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

import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * A store which lets somebody else act in the moment between the guards being read and the lease being
 * taken. That moment is not reachable otherwise: every call here answers at once, so a test asking for
 * a lease has already finished asking by the time it could interleave anything with it.
 *
 * <p>Constructed explicitly around another store, like every implementation here.
 */
final class RacedAcquireStore implements CoordinationStore {

    private final CoordinationStore delegate;
    private final Runnable meanwhile;

    RacedAcquireStore(final CoordinationStore delegate, final Runnable meanwhile) {
        this.delegate = delegate;
        this.meanwhile = meanwhile;
    }

    @Override
    public CompletionStage<LeaseAttempt> tryAcquire(final String key,
                                                    final String ownerId,
                                                    final Duration ttl) {
        meanwhile.run();
        return delegate.tryAcquire(key, ownerId, ttl);
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
        return delegate.awaitChange(key, sinceVersion, maxWait);
    }

    @Override
    public CompletionStage<Boolean> renew(final String key, final LeaseHandle handle, final Duration ttl) {
        return delegate.renew(key, handle, ttl);
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
