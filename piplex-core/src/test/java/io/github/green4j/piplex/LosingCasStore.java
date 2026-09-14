/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex;

import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.Entry;
import io.github.green4j.piplex.store.LeaseAttempt;
import io.github.green4j.piplex.store.LeaseHandle;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A store whose {@code compareAndSet} never lands, so that the end of a bounded retry loop can be
 * reached without needing a second writer racing the first for real.
 *
 * <p>It is the honest shape of losing, not a thrown error: the read succeeds, the write is refused, and
 * the caller is left to look again -- which is exactly what a lost CAS is. It counts the attempts, so a
 * test can say how many were made rather than only that the loop ended.
 *
 * <p>Constructed explicitly around another store, like every implementation here.
 */
public final class LosingCasStore implements CoordinationStore {

    private final CoordinationStore delegate;
    private final AtomicInteger attempts = new AtomicInteger();

    /**
     * @param delegate the store every other call goes to
     */
    public LosingCasStore(final CoordinationStore delegate) {
        this.delegate = delegate;
    }

    /**
     * @return how many writes have been refused
     */
    public int attempts() {
        return attempts.get();
    }

    @Override
    public CompletionStage<Boolean> compareAndSet(final String key,
                                                  final String expectedVersion,
                                                  final String value) {
        attempts.incrementAndGet();
        return CompletableFuture.completedFuture(Boolean.FALSE);
    }

    @Override
    public CompletionStage<Entry> get(final String key) {
        return delegate.get(key);
    }

    @Override
    public CompletionStage<Entry> awaitChange(final String key,
                                              final String sinceVersion,
                                              final Duration maxWait) {
        return delegate.awaitChange(key, sinceVersion, maxWait);
    }

    @Override
    public CompletionStage<LeaseAttempt> tryAcquire(final String key,
                                                    final String ownerId,
                                                    final Duration ttl) {
        return delegate.tryAcquire(key, ownerId, ttl);
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
