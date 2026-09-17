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
import java.util.concurrent.atomic.AtomicInteger;

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
    private final AtomicInteger watchesAsked = new AtomicInteger();
    private volatile boolean renews = true;
    private volatile boolean renewable = true;
    private volatile boolean watches = true;
    private volatile boolean answers = true;
    private volatile String unreadable;
    private volatile String unreadableByRead;
    private volatile String silent;
    private volatile boolean takesWatchesAtAll = true;
    private volatile boolean takesReadsAtAll = true;
    private volatile boolean confirmsWatches = true;
    private volatile boolean releases = true;

    UnreachableStore(final CoordinationStore delegate) {
        this.delegate = delegate;
    }

    void stopAnsweringRenewals() {
        renews = false;
    }

    void answerRenewalsAgain() {
        renews = true;
    }

    /**
     * Refuses renewals rather than failing them: the definite answer of a store which knows the lease
     * is not this caller's any more. Nothing an unreachable store can ever say.
     */
    void refuseRenewals() {
        renewable = false;
    }

    /**
     * Takes every call and answers none of it -- the open, silent connection. Nothing here fails, so
     * nothing counts a grace period; what is left to end a run is its own reckoning of the lease.
     */
    void goSilent() {
        answers = false;
    }

    void stopAnsweringWatches() {
        watches = false;
    }

    void answerWatchesAgain() {
        watches = true;
    }

    /**
     * Stops answering reads of one key alone, which is what an ACL narrowed by hand looks like from
     * here: everything else about the cluster keeps working, that key included as far as the lease on
     * it is concerned.
     *
     * @param key the key which may no longer be read
     */
    void stopAnsweringReadsOf(final String key) {
        unreadable = key;
    }

    /**
     * Stops answering the plain read of one key while its watch goes on answering. The shape a partial
     * outage takes when the watch is a poll that keeps a stale best answer to fall back on and the
     * read is a consensus round that has nothing to fall back on.
     *
     * @param key the key which may no longer be read outright
     */
    void stopAnsweringPlainReadsOf(final String key) {
        unreadableByRead = key;
    }

    /**
     * Takes every call about one key and answers none of it, while the rest of the cluster carries on.
     * The worst shape of the same trouble as {@link #stopAnsweringReadsOf(String)}: nothing fails, so
     * nothing arrives anywhere to be counted, and the lease on the work goes on being renewed happily.
     *
     * @param key the key nothing will be said about again
     */
    void goSilentOn(final String key) {
        silent = key;
    }

    /**
     * Refuses to take a watch at all, throwing where the call is made rather than failing the stage it
     * returned. What a client closed under a run in flight does -- which is what saving the settings on
     * a controller does to it.
     */
    void refuseWatchesOutright() {
        takesWatchesAtAll = false;
    }

    /**
     * Refuses to take a read at all, the same way {@link #refuseWatchesOutright()} refuses a watch.
     */
    void refuseReadsOutright() {
        takesReadsAtAll = false;
    }

    /**
     * Answers watches at the end of their wait with the newest state the store could see rather than
     * with what it saw then -- a watch whose late polls all failed. Nothing fails, and the answer is a
     * whole wait old.
     */
    void stopConfirmingWatches() {
        confirmsWatches = false;
    }

    /**
     * Fails every release, so the lease a run gave back may well still be standing.
     */
    void stopAnsweringReleases() {
        releases = false;
    }

    int watchesAsked() {
        return watchesAsked.get();
    }

    @Override
    public CompletionStage<Boolean> renew(final String key, final LeaseHandle handle, final Duration ttl) {
        if (!answers) {
            return new CompletableFuture<>();
        }
        if (!renews) {
            return CompletableFuture.failedFuture(new IOException("No route to the cluster"));
        }
        if (!renewable) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        return delegate.renew(key, handle, ttl);
    }

    @Override
    public CompletionStage<Entry> get(final String key) {
        if (!takesReadsAtAll) {
            throw new IllegalStateException("The client is closed");
        }
        if (!answers || key.equals(silent)) {
            return new CompletableFuture<>();
        }
        if (key.equals(unreadable) || key.equals(unreadableByRead)) {
            return CompletableFuture.failedFuture(new IOException("Permission denied on '" + key + "'"));
        }
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
        watchesAsked.incrementAndGet();
        if (!takesWatchesAtAll) {
            throw new IllegalStateException("The client is closed");
        }
        if (!answers || key.equals(silent)) {
            return new CompletableFuture<>();
        }
        if (key.equals(unreadable)) {
            return CompletableFuture.failedFuture(new IOException("Permission denied on '" + key + "'"));
        }
        if (!watches) {
            return CompletableFuture.failedFuture(new IOException("No route to the cluster"));
        }
        if (!confirmsWatches) {
            return delegate.awaitChange(key, sinceVersion, maxWait).thenApply(Entry::unconfirmed);
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
        if (!answers) {
            return new CompletableFuture<>();
        }
        if (!releases) {
            return CompletableFuture.failedFuture(new IOException("No route to the cluster"));
        }
        return delegate.release(key, handle);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
