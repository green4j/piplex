/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.exclusive;

import io.github.green4j.piplex.TimeSource;
import io.github.green4j.piplex.milestone.Milestones;
import io.github.green4j.piplex.milestone.PublishResult;
import io.github.green4j.piplex.observe.PiplexObserver;
import io.github.green4j.piplex.observe.RunRef;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.Entry;
import io.github.green4j.piplex.store.LeaseHandle;
import io.github.green4j.piplex.store.Watch;
import io.github.green4j.piplex.switches.Switch;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Ownership while it lasts: the lease kept alive, and the two keys which can take it away watched for
 * as long as the run does.
 *
 * <p>The lease has a <b>term</b> here, and everything this class does is bounded by it. The term is
 * what the store said was left of the lease when it was handed over, read against the local monotonic
 * clock before the acquisition was even asked for, so it cannot be later than the store's own reckoning.
 * A holder is allowed to believe it owns the work until the term is out and not one moment longer --
 * past it, the lease is somebody else's to take, and a holder which had not noticed is the overlap this
 * whole mechanism exists to keep short.
 */
final class HeldAdmission implements Admitted {

    /**
     * The closest together two renewals are ever asked for.
     *
     * <p>Attempts are paced at half of what is left of the term, so that one which fails still leaves
     * room for another. Left at that alone, a store which fails immediately would be asked again as
     * fast as the scheduler allows once what is left is down to nothing.
     */
    private static final Duration SOONEST_RENEW = Duration.ofMillis(1);

    private final CoordinationStore store;
    private final Milestones milestones;
    private final ExclusiveRequest request;
    private final LeaseHandle handle;
    private final TimeSource time;
    private final String leaseKey;
    private final PiplexObserver observer;
    private final RunRef ref;
    private final long guardsReadAtNanos;
    private final Object monitor = new Object();
    private final List<Consumer<Revocation>> listeners = new ArrayList<>();
    private final List<Guard> guards = new ArrayList<>();

    private volatile boolean held = true;
    private volatile long leaseValidUntilNanos;
    private volatile long lastRenewedNanos;
    // Whether a renewal is out with nothing heard back about it -- refused, or never answered at all.
    // What the give-up timer says happened is decided on this rather than on which of its two deadlines
    // was arithmetically the smaller: with the default grace of one lease the two are the same instant,
    // and a tie would then report every unreachable store as a lease that simply ran out.
    private volatile boolean renewalOutstanding;
    private Revocation revocation;
    // What a listener or the observer threw while a revocation was told, owed to the release after it.
    private RuntimeException revokedWith;
    // One stage for one release, handed to whoever asks. Two callers must not both be told the lease
    // is back when only one of them asked the store for it -- and the first of them has not finished.
    private CompletableFuture<Void> releasing;
    // Settles once the milestone of completeAndRelease() has been written, or has failed to be. A
    // release() asked for in between waits for it: the lease going first is the redo it exists to stop.
    private CompletableFuture<Void> completing;
    private TimeSource.Cancellable renewTimer;
    private TimeSource.Cancellable giveUpTimer;

    HeldAdmission(final CoordinationStore store,
                  final Milestones milestones,
                  final ExclusiveRequest request,
                  final LeaseHandle handle,
                  final TimeSource time,
                  final String leaseKey,
                  final PiplexObserver observer,
                  final RunRef ref,
                  final long acquiredAtNanos,
                  final Duration remaining,
                  final long guardsReadAtNanos) {
        this.store = store;
        this.milestones = milestones;
        this.request = request;
        this.handle = handle;
        this.time = time;
        this.leaseKey = leaseKey;
        this.observer = observer;
        this.ref = ref;
        this.guardsReadAtNanos = guardsReadAtNanos;
        this.lastRenewedNanos = acquiredAtNanos;
        this.leaseValidUntilNanos = acquiredAtNanos + termOf(remaining, request.lease()).toNanos();
    }

    /**
     * The shorter of the two figures, always. A lease recovered after an acquisition whose outcome was
     * never learnt has been running since that acquisition, and what is left of it is a fraction of the
     * lease this request asked for. A store reporting more than was asked for is not believed either.
     *
     * @param remaining what the store says is left, or {@code null} when it does not say
     * @param lease     what was asked for
     * @return how long the lease may be believed in, counted from before it was asked for
     */
    static Duration termOf(final Duration remaining, final Duration lease) {
        return remaining == null || remaining.compareTo(lease) > 0 ? lease : remaining;
    }

    void start(final String designationVersion, final String switchVersion, final String ownSwitchVersion) {
        armGiveUp(lastRenewedNanos, leaseValidUntilNanos);
        scheduleRenew();
        if (request.designatedBy() != null) {
            guard(new Guard(ExclusiveRuns.designationKey(request.designatedBy()), "designation",
                    this::designationSays)).begin(designationVersion);
        }
        if (request.enabledBy() != null) {
            guard(new Guard(ExclusiveRuns.switchKey(request.enabledBy()), "switch",
                    this::switchSays)).begin(switchVersion);
            guard(new Guard(ExclusiveRuns.switchKey(ExclusiveRuns.ownSwitch(request)), "switch",
                    this::switchSays)).begin(ownSwitchVersion);
        }
    }

    // Kept so that the end of the admission can call their timers off: a grace period is as long as
    // somebody configured it, and an admission it points at is kept in memory for all of it.
    private Guard guard(final Guard guard) {
        synchronized (monitor) {
            guards.add(guard);
        }
        return guard;
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
        // The term as well as the flag. Losing the lease is learnt from the store and the flag is how
        // that arrives, but a term which has run out is known here without asking anybody: the answer
        // is already no, and a caller about to do something irreversible is the last one who should be
        // told a scheduler hop later than that.
        return held && !time.deadlinePassed(leaseValidUntilNanos);
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
        final Ending ending;
        final CompletableFuture<Void> released;
        synchronized (monitor) {
            if (releasing != null) {
                return releasing;
            }
            if (completing != null) {
                return completing.thenCompose(published -> release());
            }
            releasing = new CompletableFuture<>();
            released = releasing;
            // After a revocation everything but the store has been done, and told, already.
            ending = held ? end(null) : null;
        }
        final RuntimeException byACallback = ending == null ? null : tell(ending);
        store.release(leaseKey, handle)
                .whenComplete((ignored, error) -> ended(released, thrownOnTheWayOut(byACallback), error));
        return released;
    }

    @Override
    public CompletionStage<PublishResult> completeAndRelease() {
        if (request.completedWhen() == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Nothing to complete: the request for '" + request.key() + "' names no completedWhen"));
        }
        final CompletableFuture<Void> settled;
        synchronized (monitor) {
            if (releasing != null || completing != null) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "The admission for '" + request.key() + "' is already being given back"));
            }
            settled = isHeld() ? new CompletableFuture<>() : null;
            completing = settled;
        }
        final CompletionStage<PublishResult> published = settled != null
                ? publishCompletion()
                : CompletableFuture.failedFuture(new IllegalStateException(
                        "Ownership of '" + request.key() + "' is gone, so '" + request.completedWhen()
                                + "' was not published"));
        // Released whatever the publish did, and only once it is settled. Its failure is the one
        // reported; the release's rides along.
        return published
                .handle((result, unpublished) -> {
                    if (settled != null) {
                        synchronized (monitor) {
                            completing = null;
                        }
                        // Before the release below, so a release() that waited on this joins that one.
                        settled.complete(null);
                    }
                    return release().handle((ignored, unreleased) -> {
                        if (unpublished == null && unreleased == null) {
                            return CompletableFuture.completedFuture(result);
                        }
                        final Throwable cause = unwrapped(unpublished != null ? unpublished : unreleased);
                        if (unpublished != null && unreleased != null) {
                            cause.addSuppressed(unwrapped(unreleased));
                        }
                        return CompletableFuture.<PublishResult>failedFuture(cause);
                    }).thenCompose(outcome -> outcome);
                })
                .thenCompose(outcome -> outcome);
    }

    // A publish that throws instead of failing its stage must still settle the completion.
    private CompletionStage<PublishResult> publishCompletion() {
        try {
            return milestones.publish(request.completedWhen(), request.generation(),
                    request.ownerId(), request.runId());
        } catch (final RuntimeException thrown) {
            return CompletableFuture.failedFuture(thrown);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Also how an ask nobody is waiting on any more is dropped. The lease may by now be somebody
     * else's in this process: a retry under the same identity is told it already holds it. Giving it
     * back would take it from under that retry, so it is left to the retry, or to lapse.
     */
    @Override
    public void abandon() {
        synchronized (monitor) {
            if (releasing != null) {
                return;
            }
            releasing = CompletableFuture.completedFuture(null);
            if (held) {
                end(null);
            }
        }
    }

    /**
     * Ends the admission for a reason, once, and leaves the lease where it is.
     *
     * <p>The lease is not given back here. The host is told to stop the work, and stopping can take a
     * while -- a Jenkins body is cancelled asynchronously -- so the lease goes back through
     * {@link #release()} once the work has actually stopped. Until then nothing renews it, so a host
     * which never calls release() leaves it to lapse within its term.
     *
     * <p>Everything told is told outside the monitor and each on its own, because none of it is this
     * class's code. A listener that throws must not keep the rest from hearing; what it threw reaches
     * whoever calls {@link #release()} afterwards instead of being swallowed.
     *
     * @param cause why the run may not continue
     */
    private void revoke(final Revocation cause) {
        final Ending ending;
        synchronized (monitor) {
            if (!held) {
                return;
            }
            ending = end(cause);
        }
        revoked(ending);
    }

    private void revoked(final Ending ending) {
        final RuntimeException thrown = tell(ending);
        if (thrown != null) {
            synchronized (monitor) {
                revokedWith = thrown;
            }
        }
    }

    private RuntimeException thrownOnTheWayOut(final RuntimeException byRelease) {
        if (byRelease != null) {
            return byRelease;
        }
        synchronized (monitor) {
            return revokedWith;
        }
    }

    /**
     * Claims the end of the admission. Only under the monitor, and only while it is held.
     *
     * @param cause why the run may not continue, or {@code null} when it gave the work back itself
     * @return what is left to tell outside the monitor
     */
    private Ending end(final Revocation cause) {
        held = false;
        revocation = cause;
        final List<Consumer<Revocation>> told = cause == null ? List.of() : List.copyOf(listeners);
        listeners.clear();
        if (renewTimer != null) {
            renewTimer.cancel();
        }
        if (giveUpTimer != null) {
            giveUpTimer.cancel();
        }
        for (final Guard guard : guards) {
            guard.cancelTimers();
        }
        // The two watches are not cancelled, because a store's watch is a poll bounded by the wait it
        // was given and the interface has no way to call one off early. What ends them is this flag:
        // the answer, when it comes, is dropped, and nothing re-arms. The cost of that is one more poll
        // of each, and at most one lease of them.
        return new Ending(cause, told);
    }

    /**
     * @param ending what was claimed
     * @return the first thing a listener or the observer threw, or {@code null}
     */
    private RuntimeException tell(final Ending ending) {
        final Revocation cause = ending.cause();
        RuntimeException thrown = null;
        for (final Consumer<Revocation> listener : ending.told()) {
            thrown = alsoThrown(thrown, () -> listener.accept(cause));
        }
        return alsoThrown(thrown, () -> {
            if (cause == null) {
                observer.released(ref);
            } else {
                observer.revoked(ref, cause);
            }
        });
    }

    /**
     * An end claimed under the monitor, and still to be told.
     *
     * @param cause why, or {@code null} for a release
     * @param told  the listeners to tell
     */
    private record Ending(Revocation cause, List<Consumer<Revocation>> told) {
    }

    /**
     * @param released    the stage whoever asked for the release is waiting on
     * @param byACallback what a listener or the observer threw, or {@code null}
     * @param error       what the store said, or {@code null} when it gave the lease back
     */
    private static void ended(final CompletableFuture<Void> released,
                              final RuntimeException byACallback,
                              final Throwable error) {
        // The callback's throw first: the store's failure means a lease that lapses on its own soon
        // enough, while that one is a host which has not stopped the work it was told to stop. The
        // store's failure still rides along: it says the lease may be standing for a while yet.
        if (byACallback != null) {
            if (error != null) {
                byACallback.addSuppressed(unwrapped(error));
            }
            released.completeExceptionally(byACallback);
        } else if (error != null) {
            released.completeExceptionally(error);
        } else {
            released.complete(null);
        }
    }

    static Throwable unwrapped(final Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    /**
     * @param first what something told before this one threw, or {@code null}
     * @param tell  what to tell
     * @return the first throw of the two, so that the one nearest the cause is the one reported
     */
    private static RuntimeException alsoThrown(final RuntimeException first, final Runnable tell) {
        try {
            tell.run();
        } catch (final RuntimeException thrown) {
            if (first == null) {
                return thrown;
            }
            first.addSuppressed(thrown);
        }
        return first;
    }

    // No order to invert here: this timer is armed after armGiveUp, so a scheduler which refuses it
    // leaves the give-up timer standing and the run still ends at its deadline.
    private void scheduleRenew() {
        synchronized (monitor) {
            if (!held) {
                return;
            }
            renewTimer = time.schedule(untilNextRenew(), this::renew);
        }
    }

    /**
     * How long to wait before asking again, which is never as long as the term has left.
     *
     * <p>{@code renewEvery} while there is room for it, and half of what is left of the term once
     * there is not. The half is what makes the first attempt on a short lease -- one recovered with
     * seconds left on it -- happen while there is still time for a second one, instead of landing on
     * the expiry it was supposed to prevent.
     *
     * @return how long to wait
     */
    private Duration untilNextRenew() {
        final Duration half = time.until(giveUpAtNanos()).dividedBy(2);
        if (half.compareTo(request.renewEvery()) >= 0) {
            return request.renewEvery();
        }
        return half.compareTo(SOONEST_RENEW) < 0 ? SOONEST_RENEW : half;
    }

    /**
     * The last moment this run may still act as the owner.
     *
     * <p>The earlier of two deadlines, and the order between them is not a setting. One is the term of
     * the lease itself. The other is how long an unreachable store is tolerated, counted from the last
     * renewal that landed -- and it may only bring the moment forward. A grace period longer than the
     * lease, which is what the default of one lease becomes as soon as the lease is shortened by a
     * recovery, would otherwise have a holder waiting out a lease somebody else is already free to take.
     *
     * @return the reading it has been reached at
     */
    private long giveUpAtNanos() {
        return giveUpAtNanos(lastRenewedNanos, leaseValidUntilNanos);
    }

    /**
     * @param renewedAtNanos when the last renewal that landed was sent
     * @param validUntilNanos when the term of the lease it extended is out
     * @return the reading the run may no longer act as the owner past
     */
    private long giveUpAtNanos(final long renewedAtNanos, final long validUntilNanos) {
        final long graceEndsNanos = renewedAtNanos + request.renewalGrace().toNanos();
        return graceEndsNanos - validUntilNanos < 0 ? graceEndsNanos : validUntilNanos;
    }

    /**
     * Arms the one timer which ends the run whatever the store does, or does not do.
     *
     * <p>Every other way out of here waits for an answer: a renewal refused, a renewal that failed, a
     * designation that moved. A call which is never answered at all -- a connection that is open and
     * silent, a client whose future nothing completes -- is the case none of them covers, and it is the
     * one where a run goes on holding work whose lease has lapsed. This is what covers it.
     *
     * <p>Armed before the old one is cancelled, the same way {@link Guard#armGrace} is: a scheduler
     * which refuses -- and one shut down refuses every {@code schedule} -- then leaves the run with the
     * timer it already had instead of with none. The deadline a renewal bought is taken here too, and
     * for the same reason: put in force before the arming, a refusal would move it out from under the
     * timer that survived, which then finds nothing due when it fires and returns without re-arming.
     *
     * @param renewedAtNanos  when the renewal being acted on was sent
     * @param validUntilNanos when the term it bought is out
     */
    private void armGiveUp(final long renewedAtNanos, final long validUntilNanos) {
        synchronized (monitor) {
            if (!held) {
                return;
            }
            final TimeSource.Cancellable armed = time.schedule(
                    time.until(giveUpAtNanos(renewedAtNanos, validUntilNanos)), this::giveUp);
            if (giveUpTimer != null) {
                giveUpTimer.cancel();
            }
            giveUpTimer = armed;
            lastRenewedNanos = renewedAtNanos;
            leaseValidUntilNanos = validUntilNanos;
            // Nothing is out unanswered any more: either a renewal just landed, or this is the
            // acquisition itself.
            renewalOutstanding = false;
        }
    }

    private void giveUp() {
        revokeIfDue(this::giveUpAtNanos, this::gaveUp);
    }

    /**
     * What the give-up timer has to say, decided where it is acted on.
     *
     * <p>Two different sentences to whoever reads the build, and the difference is what was heard from
     * the store rather than which deadline was the smaller. The store not answering says the lease may
     * well still be standing and nobody could be asked; a term that ran out under a store which was
     * answering all along says the lease is gone. Told apart on the evidence, because the two deadlines
     * coincide exactly whenever the grace period is left at its default of one lease.
     *
     * @return why the run may not continue
     */
    private Revocation gaveUp() {
        if (renewalOutstanding) {
            return new Revocation(Revocation.Reason.RENEWAL_FAILED, null,
                    "the store could not be reached for " + request.renewalGrace());
        }
        return new Revocation(Revocation.Reason.LEASE_LOST, null,
                "the lease ran out before a renewal extended it");
    }

    /**
     * Ends the admission, but only if the deadline a timer was armed for really has passed.
     *
     * <p>Cancelling a scheduled action does not stop one already running, so every timer here can be
     * one that something landing a moment ago has just replaced. The deadline is read again for that
     * reason -- revoking a lease that was extended a moment ago is the one mistake here that stops a
     * run for no reason at all -- and it is read, decided on, and claimed without letting go of the
     * monitor, because reading it and acting on it as two steps leaves exactly the same race in the gap
     * between them.
     *
     * @param deadlineNanos the deadline to read again, now
     * @param cause         why the run may not continue, asked for only once it has passed
     */
    private void revokeIfDue(final LongSupplier deadlineNanos, final Supplier<Revocation> cause) {
        final Ending ending;
        synchronized (monitor) {
            if (!held || !time.deadlinePassed(deadlineNanos.getAsLong())) {
                return;
            }
            // Claimed whole under the monitor whatever would have moved the deadline takes: a renewal
            // landing now finds the admission already ending, and a release() landing now finds it
            // revoked rather than turning it into a release nobody is told about.
            ending = end(cause.get());
        }
        revoked(ending);
    }

    private void renew() {
        if (!held) {
            return;
        }
        // Read before the call rather than after it: what comes back says the lease now lasts for the
        // ttl asked for, and the store started counting no later than the moment it was asked.
        final long sentAtNanos = time.nanos();
        // Out with nothing heard back yet, and it stays that way until the store says otherwise. A call
        // which is never answered at all leaves it standing, which is the point: silence and a refusal
        // are the same amount of evidence about the lease.
        renewalOutstanding = true;
        store.renew(leaseKey, handle, request.lease()).whenComplete((extended, error) -> {
            if (!held) {
                return;
            }
            if (error != null) {
                onRenewUnreachable();
            } else if (Boolean.TRUE.equals(extended)) {
                extended(sentAtNanos);
            } else {
                revoke(new Revocation(Revocation.Reason.LEASE_LOST, null));
            }
        });
    }

    private void extended(final long sentAtNanos) {
        // Everything the renewal bought goes into force inside here: under the same monitor the give-up
        // timer claims the admission under, so a renewal landing as that timer fires either moves the
        // deadline before it is read or finds the run already ending. Read and acted on outside it, the
        // two would both win.
        armGiveUp(sentAtNanos, sentAtNanos + request.lease().toNanos());
        scheduleRenew();
    }

    private void onRenewUnreachable() {
        // The store cannot be reached, so the lease cannot be extended and it cannot be learnt whether
        // anybody else has taken it. Tolerated, and only trying again is left to do here: for how long
        // it is tolerated is armGiveUp's, which holds the same deadline whether the attempts come back
        // failed or never come back at all.
        if (time.deadlinePassed(giveUpAtNanos())) {
            return;
        }
        scheduleRenew();
    }

    /**
     * What the designation says about this run, now.
     *
     * <p>Only the state matters, never the sequence of changes that got here: if the designation
     * flipped away and back while a watch was in flight, the coalesced answer -- still mine -- is the
     * right one, and revoking on the intermediate would have been the mistake.
     *
     * @param key   the key it was read from
     * @param entry what it says
     * @return why this run may not continue, or {@code null} while it may
     */
    private Revocation designationSays(final String key, final Entry entry) {
        final Designation seen = entry.exists() ? Designation.parse(entry.value()) : null;
        if (seen == null) {
            // Removed, which is what "nobody is designated" looks like -- and it is the answer a
            // candidate asking from scratch gets. A run in flight is told the same thing, or who may
            // run would depend on when the run happened to start.
            return new Revocation(Revocation.Reason.DESIGNATION_CHANGED, null,
                    "the designation at '" + key + "' was removed");
        }
        if (!seen.owner().equals(request.ownerId())) {
            return new Revocation(Revocation.Reason.DESIGNATION_CHANGED, seen.owner());
        }
        return null;
    }

    /**
     * @param key   the key it was read from
     * @param entry what it says
     * @return why this run may not continue, or {@code null} while it may
     */
    private Revocation switchSays(final String key, final Entry entry) {
        final Switch state = entry.exists() ? Switch.parse(entry.value()) : Switch.ENABLED;
        if (state.enabled()) {
            return null;
        }
        // The operator's own words, which is the whole point of switching something off with a reason.
        // Without them the build says DISABLED and nothing about which incident.
        return new Revocation(Revocation.Reason.DISABLED, null, state.reason());
    }

    /**
     * One key which can take the admission away, watched for as long as the run lasts -- and how long
     * it has been since anything was actually learnt about it.
     *
     * <p>That second part is the whole reason this is an object rather than a method. A watch which
     * fails says nothing about the key it watches, and the renewal loop cannot answer for it: a lease
     * renewed happily while a designation cannot be read is the ordinary shape of an ACL that was
     * narrowed by hand, or of one key's requests taking a route that is down. Left to try again for
     * ever, the run keeps the lease for its whole length with nothing left watching what may revoke it,
     * which is the one failure this class exists to prevent, failing open.
     */
    private final class Guard {

        private final String key;
        private final String what;
        private final Decision decision;

        private volatile long lastReadNanos;
        private TimeSource.Cancellable graceTimer;
        private TimeSource.Cancellable paceTimer;

        private Guard(final String key, final String what, final Decision decision) {
            this.key = key;
            this.what = what;
            this.decision = decision;
            // The admission was granted on a read of this key, so that is what has been learnt and when:
            // the reading taken before that read was sent, which cannot be later than the read itself.
            this.lastReadNanos = guardsReadAtNanos;
        }

        private void begin(final String sinceVersion) {
            armGrace(lastReadNanos);
            watch(sinceVersion);
        }

        /**
         * Arms the one timer which ends the run whatever this key does, or does not do.
         *
         * <p>A timer rather than a test made where an answer comes back, because the case that matters
         * most is the one where no answer comes back at all -- a connection that is open and silent, a
         * client whose future nothing completes -- and the store has no timeout to give a read. A grace
         * period only reached by arriving at it is not a bound on anything, and a lease renewed happily
         * while nothing is left watching what may revoke it is the one failure this class exists to
         * prevent.
         *
         * <p>Armed before the old one is cancelled, so that a scheduler which refuses leaves the run
         * with the timer it already had instead of with none. The reading the grace is counted from is
         * taken here too, and for the same reason: recorded before the arming, a refusal would move the
         * deadline out from under the timer that survived, which then finds nothing due when it fires
         * and returns without re-arming -- no timer and no watch, which is the state this class exists
         * to prevent.
         *
         * @param readAtNanos when the key was last learnt about, which the grace is counted from
         */
        private void armGrace(final long readAtNanos) {
            synchronized (monitor) {
                if (!held) {
                    return;
                }
                final long endsAtNanos = readAtNanos + request.guardGrace().toNanos();
                final TimeSource.Cancellable armed =
                        time.schedule(time.until(endsAtNanos), this::graceRanOut);
                if (graceTimer != null) {
                    graceTimer.cancel();
                }
                graceTimer = armed;
                lastReadNanos = readAtNanos;
            }
        }

        // Only under the monitor, the one both timers are replaced under.
        private void cancelTimers() {
            if (graceTimer != null) {
                graceTimer.cancel();
            }
            if (paceTimer != null) {
                paceTimer.cancel();
            }
        }

        private long graceEndsAtNanos() {
            return lastReadNanos + request.guardGrace().toNanos();
        }

        private void graceRanOut() {
            revokeIfDue(this::graceEndsAtNanos, () -> new Revocation(
                    Revocation.Reason.GUARD_UNREACHABLE, null,
                    "the " + what + " at '" + key + "' has not been readable for "
                            + request.guardGrace()));
        }

        private void watch(final String sinceVersion) {
            if (!held) {
                return;
            }
            store.awaitChange(key, sinceVersion, within(), Watch.URGENT).whenComplete((entry, error) -> {
                if (!held) {
                    return;
                }
                // An unconfirmed answer is the newest the store could see rather than what it saw at
                // the end of the wait, so it says nothing about the key now -- which is the same
                // amount of evidence as a watch that failed, and takes the same path. Credited as a
                // read it would extend this grace period by a full watch window of not knowing.
                if (error != null || !entry.confirmed()) {
                    reread(sinceVersion);
                    return;
                }
                if (permits(entry)) {
                    watch(entry.version());
                }
            });
        }

        /**
         * How long one watch may last.
         *
         * <p>A lease, and never more than half of what is left of the grace period. A watch is a
         * long-lived call which hangs for the whole wait it was given, so its wait is the pace of this
         * loop the way {@code renewEvery} is the pace of the renewal loop -- and given the whole of the
         * remaining grace it would be due to answer at the very moment the grace runs out, leaving no
         * room for the read that this loop depends on and putting a healthy watch in a dead heat with
         * the deadline that ends the run. Half, and an answer is always in before then.
         *
         * @return the wait to give the store
         */
        private Duration within() {
            final Duration half = time.until(graceEndsAtNanos()).dividedBy(2);
            return half.compareTo(request.lease()) < 0 ? half : request.lease();
        }

        /**
         * Asks the plain question after the standing one failed.
         *
         * <p>A watch is a long-lived call and fails on its own schedule -- a timeout, a coordinator
         * that went away mid-poll -- and a failed one is not an answer about the key. Re-arming it and
         * hoping is a guess. A read is one round trip against the same cluster and linearizable, so if
         * it lands, the answer the watch was waiting for is in it, and this guard is healthy again.
         *
         * <p>When it does not land either, nothing about this key has been learnt since
         * {@link #lastReadNanos}, and that is what the grace period is counted against.
         *
         * @param sinceVersion the version the failed watch was waiting past
         */
        private void reread(final String sinceVersion) {
            store.get(key).whenComplete((entry, error) -> {
                if (!held) {
                    return;
                }
                if (error != null) {
                    rearm(() -> watch(sinceVersion));
                    return;
                }
                if (permits(entry)) {
                    // Paced rather than at once: what failed is the watch, and asking for it again
                    // in the same breath is a loop as fast as the store can refuse.
                    final String version = entry.version();
                    rearm(() -> watch(version));
                }
            });
        }

        /**
         * @param entry what the key says
         * @return whether the run may continue; it has been revoked when it may not
         */
        private boolean permits(final Entry entry) {
            try {
                // Under the same monitor the grace timer reads it under, and re-armed here rather than
                // where a watch is: this is the one place something was actually learnt about the key.
                armGrace(time.nanos());
            } catch (final RuntimeException refused) {
                // A scheduler that refuses one timer refuses the next one too, so there is nothing left
                // to pace this loop with. Left to itself the throw goes into the whenComplete of a watch
                // or a re-read, where nothing reports it, and the guard is over without saying so.
                revoke(new Revocation(Revocation.Reason.GUARD_UNREACHABLE, null,
                        "the " + what + " at '" + key + "' can no longer be watched: " + refused));
                return false;
            }
            final Revocation cause;
            try {
                cause = decision.of(key, entry);
            } catch (final RuntimeException unreadable) {
                // Ends the run: left to itself the parse failure kills the watch silently, and the
                // lease goes on being renewed over work nothing is watching.
                revoke(new Revocation(Revocation.Reason.GUARD_UNREADABLE, null,
                        ExclusiveRuns.unreadableDetail(key, what, unreadable)));
                return false;
            }
            if (cause == null) {
                return true;
            }
            revoke(cause);
            return false;
        }

        // No order to invert here either: this timer paces the loop and does not end the run, so a
        // scheduler which refuses it leaves the grace timer standing and the run still ends at it.
        // Kept, so that the end of the admission can call it off.
        private void rearm(final Runnable watch) {
            synchronized (monitor) {
                if (!held) {
                    return;
                }
                paceTimer = time.schedule(soon(), watch);
            }
        }

        /**
         * How long to wait before asking about this key again, which is never as long as the grace has
         * left.
         *
         * <p>{@code renewEvery} while there is room for it, and half of what is left of the grace once
         * there is not -- the same shape the renewal loop paces itself by, and for the same reason: at a
         * flat interval the last attempt before the grace is out lands after it, and the grace period
         * stops being what it says it is.
         *
         * @return how long to wait
         */
        private Duration soon() {
            final Duration half = time.until(graceEndsAtNanos()).dividedBy(2);
            if (half.compareTo(request.renewEvery()) >= 0) {
                return request.renewEvery();
            }
            return half.compareTo(SOONEST_RENEW) < 0 ? SOONEST_RENEW : half;
        }
    }

    /**
     * What one key means to this run.
     */
    @FunctionalInterface
    private interface Decision {

        /**
         * @param key   the key it was read from
         * @param entry what it says
         * @return why the run may not continue, or {@code null} while it may
         */
        Revocation of(String key, Entry entry);
    }
}
