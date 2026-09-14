/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.exclusive;

import io.github.green4j.piplex.TimeSource;
import io.github.green4j.piplex.observe.PiplexObserver;
import io.github.green4j.piplex.observe.RunRef;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.Entry;
import io.github.green4j.piplex.store.LeaseHandle;
import io.github.green4j.piplex.switches.Switch;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Ownership while it lasts: the lease kept alive, and the two keys which can take it away watched for
 * as long as the run does.
 */
final class HeldAdmission implements Admitted {

    private final CoordinationStore store;
    private final ExclusiveRequest request;
    private final LeaseHandle handle;
    private final TimeSource time;
    private final String leaseKey;
    private final PiplexObserver observer;
    private final RunRef ref;
    private final Object monitor = new Object();
    private final List<Consumer<Revocation>> listeners = new ArrayList<>();

    private volatile boolean held = true;
    private Revocation revocation;
    private long lastRenewedNanos;
    private TimeSource.Cancellable renewTimer;

    HeldAdmission(final CoordinationStore store,
                  final ExclusiveRequest request,
                  final LeaseHandle handle,
                  final TimeSource time,
                  final String leaseKey,
                  final PiplexObserver observer,
                  final RunRef ref) {
        this.store = store;
        this.request = request;
        this.handle = handle;
        this.time = time;
        this.leaseKey = leaseKey;
        this.observer = observer;
        this.ref = ref;
        this.lastRenewedNanos = time.nanos();
    }

    void start(final String designationVersion, final String switchVersion) {
        scheduleRenew();
        if (request.designatedBy() != null) {
            watchDesignation(designationVersion);
        }
        if (request.enabledBy() != null) {
            watchSwitch(switchVersion);
        }
    }

    @Override
    public String ownerId() {
        return request.ownerId();
    }

    @Override
    public String runId() {
        return request.runId();
    }

    @Override
    public long fencingToken() {
        return handle.fencingToken();
    }

    @Override
    public boolean isHeld() {
        return held;
    }

    @Override
    public void onRevoked(final Consumer<Revocation> listener) {
        final Revocation already;
        synchronized (monitor) {
            already = revocation;
            if (already == null) {
                listeners.add(listener);
            }
        }
        // Told at once when it has already happened, so that registering after the fact is not a race.
        if (already != null) {
            listener.accept(already);
        }
    }

    @Override
    public CompletionStage<Void> release() {
        return stop(null);
    }

    private void revoke(final Revocation cause) {
        stop(cause);
    }

    private CompletionStage<Void> stop(final Revocation cause) {
        final List<Consumer<Revocation>> told;
        synchronized (monitor) {
            if (!held) {
                return CompletableFuture.completedFuture(null);
            }
            held = false;
            revocation = cause;
            told = cause == null ? List.of() : List.copyOf(listeners);
            listeners.clear();
            if (renewTimer != null) {
                renewTimer.cancel();
            }
        }
        told.forEach(listener -> listener.accept(cause));
        if (cause == null) {
            observer.released(ref);
        } else {
            observer.revoked(ref, cause);
        }
        return store.release(leaseKey, handle);
    }

    private void scheduleRenew() {
        synchronized (monitor) {
            if (!held) {
                return;
            }
            renewTimer = time.schedule(request.renewEvery(), this::renew);
        }
    }

    private void renew() {
        if (!held) {
            return;
        }
        store.renew(leaseKey, handle, request.lease()).whenComplete((extended, error) -> {
            if (!held) {
                return;
            }
            if (error != null) {
                onRenewUnreachable();
            } else if (Boolean.TRUE.equals(extended)) {
                lastRenewedNanos = time.nanos();
                scheduleRenew();
            } else {
                revoke(new Revocation(Revocation.Reason.LEASE_LOST, null));
            }
        });
    }

    private void onRenewUnreachable() {
        // The store cannot be reached, so the lease cannot be extended and it cannot be learnt whether
        // anybody else has taken it. Tolerated for the grace period, then given up: acting as the owner
        // on no evidence is the worse of the two mistakes.
        if (time.deadlinePassed(lastRenewedNanos + request.renewalGrace().toNanos())) {
            revoke(new Revocation(Revocation.Reason.RENEWAL_FAILED, null));
        } else {
            scheduleRenew();
        }
    }

    private void watchDesignation(final String sinceVersion) {
        if (!held) {
            return;
        }
        final String key = ExclusiveRuns.designationKey(request.designatedBy());
        store.awaitChange(key, sinceVersion, request.lease()).whenComplete((entry, error) -> {
            if (!held) {
                return;
            }
            if (error != null) {
                rearm(() -> watchDesignation(sinceVersion));
                return;
            }
            // Only the state matters, never the sequence of changes that got here: if the designation
            // flipped away and back while this was in flight, the coalesced answer -- still mine -- is
            // the right one, and revoking on the intermediate would have been the mistake.
            final Designation seen;
            try {
                seen = entry.exists() ? Designation.parse(entry.value()) : null;
            } catch (final RuntimeException unreadable) {
                unreadable(key, "designation", unreadable);
                return;
            }
            if (seen != null && !seen.owner().equals(request.ownerId())) {
                revoke(new Revocation(Revocation.Reason.DESIGNATION_CHANGED, seen.owner()));
            } else {
                watchDesignation(entry.version());
            }
        });
    }

    private void watchSwitch(final String sinceVersion) {
        if (!held) {
            return;
        }
        final String key = ExclusiveRuns.switchKey(request.enabledBy());
        store.awaitChange(key, sinceVersion, request.lease()).whenComplete((entry, error) -> {
            if (!held) {
                return;
            }
            if (error != null) {
                rearm(() -> watchSwitch(sinceVersion));
                return;
            }
            final Switch state;
            try {
                state = switchOf(entry);
            } catch (final RuntimeException unreadable) {
                unreadable(key, "switch", unreadable);
                return;
            }
            if (!state.enabled()) {
                revoke(new Revocation(Revocation.Reason.DISABLED, null));
            } else {
                watchSwitch(entry.version());
            }
        });
    }

    /**
     * Ends the run: left to itself the parse failure kills the watch silently, and the lease goes on
     * being renewed over work nothing is watching.
     *
     * @param key        the key which could not be read
     * @param what       what that key is
     * @param unreadable why it could not be read
     */
    private void unreadable(final String key, final String what, final RuntimeException unreadable) {
        revoke(new Revocation(Revocation.Reason.GUARD_UNREADABLE, null,
                "the " + what + " at '" + key + "' cannot be read: " + unreadable.getMessage()));
    }

    private static Switch switchOf(final Entry entry) {
        return entry.exists() ? Switch.parse(entry.value()) : Switch.ENABLED;
    }

    private void rearm(final Runnable watch) {
        // A watch which failed is a reachability problem, and the renewal loop is what decides whether
        // that has gone on long enough to matter. Here it is only worth trying again.
        time.schedule(request.renewEvery(), watch);
    }
}
