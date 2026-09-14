/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.store.memory;

import io.github.green4j.piplex.TimeSource;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.Entry;
import io.github.green4j.piplex.store.LeaseAttempt;
import io.github.green4j.piplex.store.LeaseHandle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * A store which keeps everything in this JVM, for tests and for running an example without a cluster.
 *
 * <p>It is deliberately faithful about the two things which are easy to get wrong against a real store,
 * because code which passes here and fails there is worse than no fake at all:
 *
 * <ul>
 *   <li><b>Waiting coalesces.</b> Several writes while a caller waits report only the latest state.
 *       Anything which counts events rather than comparing state fails here too.</li>
 *   <li><b>A lease lapses by elapsed time, not by a timer.</b> Expiry is decided when somebody looks,
 *       exactly as a real store decides it, so an expired holder learns of it on its next call and not
 *       a moment sooner.</li>
 * </ul>
 *
 * <p>Time comes from the {@link TimeSource} handed in, so a test drives lease expiry and a four-hour park
 * without waiting for any of it. Leases are timed by elapsed nanoseconds, as in a real store -- the wall
 * clock is not consulted here at all, and there is nothing in a lease for it to be wrong about.
 */
public final class InMemoryCoordinationStore implements CoordinationStore {

    private final Object monitor = new Object();
    private final Map<String, Value> values = new HashMap<>();
    private final Map<String, Lease> leases = new HashMap<>();
    private final List<Waiter> waiters = new ArrayList<>();
    private final TimeSource time;

    /**
     * @param time where elapsed time comes from, and how a wait is timed out
     */
    public InMemoryCoordinationStore(final TimeSource time) {
        this.time = time;
    }

    @Override
    public CompletionStage<Entry> get(final String key) {
        synchronized (monitor) {
            return CompletableFuture.completedFuture(entryOf(key));
        }
    }

    @Override
    public CompletionStage<Boolean> compareAndSet(final String key,
                                                  final String expectedVersion,
                                                  final String value) {
        final List<Waiter> due;
        final Entry after;
        synchronized (monitor) {
            final Value current = values.get(key);
            final String version = current == null ? INITIAL_VERSION : current.version;
            if (!version.equals(expectedVersion)) {
                return CompletableFuture.completedFuture(Boolean.FALSE);
            }
            final long next = (current == null ? 0L : current.counter) + 1L;
            values.put(key, new Value(value, Long.toString(next), next));
            due = takeWaiters(key);
            after = entryOf(key);
        }
        // Completed outside the monitor and outside the loop over the waiter list, because a waiter's
        // continuation re-enters this store synchronously -- typically to wait again. Completing under
        // the lock would deadlock a real store and, here, mutated the list being iterated.
        complete(due, after);
        return CompletableFuture.completedFuture(Boolean.TRUE);
    }

    @Override
    public CompletionStage<Entry> awaitChange(final String key,
                                              final String sinceVersion,
                                              final Duration maxWait) {
        final CompletableFuture<Entry> result = new CompletableFuture<>();
        final Waiter waiter;
        synchronized (monitor) {
            final Entry now = entryOf(key);
            if (!now.version().equals(sinceVersion)) {
                return CompletableFuture.completedFuture(now);
            }
            waiter = new Waiter(key, result);
            waiters.add(waiter);
        }
        final TimeSource.Cancellable timeout = time.schedule(maxWait, () -> {
            final Entry now;
            synchronized (monitor) {
                if (!waiters.remove(waiter)) {
                    return; // a write got here first
                }
                now = entryOf(key);
            }
            result.complete(now);
        });
        result.whenComplete((entry, error) -> timeout.cancel());
        return result;
    }

    @Override
    public CompletionStage<LeaseAttempt> tryAcquire(final String key,
                                                    final String ownerId,
                                                    final Duration ttl) {
        synchronized (monitor) {
            final Lease held = leases.get(key);
            if (held != null && !time.deadlinePassed(held.expiresAtNanos)) {
                final Duration left = time.until(held.expiresAtNanos);
                if (held.handle.ownerId().equals(ownerId)) {
                    return CompletableFuture.completedFuture(
                            new LeaseAttempt.HeldBySelf(held.handle, left));
                }
                return CompletableFuture.completedFuture(
                        new LeaseAttempt.HeldByOther(held.handle.ownerId(), left));
            }
            final long token = (held == null ? 0L : held.handle.fencingToken()) + 1L;
            leases.put(key, new Lease(new Handle(ownerId, token), time.deadlineIn(ttl)));
            return CompletableFuture.completedFuture(
                    new LeaseAttempt.Acquired(leases.get(key).handle, ttl));
        }
    }

    @Override
    public CompletionStage<Boolean> renew(final String key, final LeaseHandle handle, final Duration ttl) {
        synchronized (monitor) {
            final Lease held = leases.get(key);
            if (held == null || !held.handle.equals(handle) || time.deadlinePassed(held.expiresAtNanos)) {
                return CompletableFuture.completedFuture(Boolean.FALSE);
            }
            leases.put(key, new Lease(held.handle, time.deadlineIn(ttl)));
            return CompletableFuture.completedFuture(Boolean.TRUE);
        }
    }

    @Override
    public CompletionStage<Void> release(final String key, final LeaseHandle handle) {
        synchronized (monitor) {
            final Lease held = leases.get(key);
            if (held != null && held.handle.equals(handle)) {
                // Kept, not removed: the fencing token has to keep climbing across releases, or it
                // guards nothing. A released lease is simply one which has already expired.
                leases.put(key, new Lease(held.handle, time.nanos()));
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    @Override
    public void close() {
        final List<Waiter> abandoned;
        synchronized (monitor) {
            values.clear();
            leases.clear();
            abandoned = List.copyOf(waiters);
            waiters.clear();
        }
        // Failed rather than dropped, and rather than answered with a state this store no longer has.
        // A real client closing ends its outstanding requests, and a waiter left holding a future that
        // will never complete is a test that hangs instead of one that says what went wrong.
        abandoned.forEach(waiter -> waiter.result.completeExceptionally(
                new IllegalStateException("the store was closed while waiting for '" + waiter.key + "'")));
    }

    private Entry entryOf(final String key) {
        final Value current = values.get(key);
        if (current == null) {
            return Entry.absent(INITIAL_VERSION);
        }
        return current.value == null
                ? Entry.absent(current.version)
                : Entry.of(current.value, current.version);
    }

    private List<Waiter> takeWaiters(final String key) {
        final List<Waiter> taken = new ArrayList<>();
        final Iterator<Waiter> iterator = waiters.iterator();
        while (iterator.hasNext()) {
            final Waiter waiter = iterator.next();
            if (waiter.key.equals(key)) {
                iterator.remove();
                taken.add(waiter);
            }
        }
        return taken;
    }

    private static void complete(final List<Waiter> waiters, final Entry entry) {
        waiters.forEach(waiter -> waiter.result.complete(entry));
    }

    private record Handle(String ownerId, long fencingToken) implements LeaseHandle {
    }

    private record Value(String value, String version, long counter) {
    }

    private record Lease(Handle handle, long expiresAtNanos) {
    }

    private record Waiter(String key, CompletableFuture<Entry> result) {
    }
}
