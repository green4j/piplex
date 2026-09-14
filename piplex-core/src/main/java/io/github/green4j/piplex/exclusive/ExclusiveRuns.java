/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.exclusive;

import io.github.green4j.piplex.TimeSource;
import io.github.green4j.piplex.milestone.Milestone;
import io.github.green4j.piplex.milestone.Milestones;
import io.github.green4j.piplex.observe.PiplexObserver;
import io.github.green4j.piplex.observe.RunRef;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.Entry;
import io.github.green4j.piplex.store.LeaseAttempt;
import io.github.green4j.piplex.store.LeaseHandle;
import io.github.green4j.piplex.switches.Switch;
import io.github.green4j.piplex.switches.Switches;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Decides which controller may run the work, and keeps deciding for as long as the run lasts.
 *
 * <p>Two policies sit behind one call, and they are different guarantees rather than settings:
 *
 * <ul>
 *   <li><b>Elected</b> -- no designation key. Whoever takes the lease runs. Right when the controllers
 *       are interchangeable.</li>
 *   <li><b>Designated</b> -- a key names the owner. Right when they are not: "the current primary
 *       region" is a fact somebody decides, and whoever happened to wake up first is the wrong
 *       answer. The lease is still taken, because it guards a second concern -- two concurrent runs by
 *       the designated owner itself.</li>
 * </ul>
 *
 * <p>Admission is held rather than granted once. Change the designation while a run is in flight and
 * the holder is revoked and stopped; a candidate parked in {@code handoverWait} takes over. That is
 * why a candidate which is not designated does not simply exit: if it did, there would be nobody left
 * to take over, and on a daily schedule the next chance would be tomorrow.
 */
public final class ExclusiveRuns {

    private static final String LEASE_PREFIX = "piplex/exclusive/";

    private final CoordinationStore store;
    private final TimeSource time;
    private final PiplexObserver observer;

    /**
     * @param store where the designation, the switch, the milestone and the lease live
     * @param time  where time comes from
     */
    public ExclusiveRuns(final CoordinationStore store, final TimeSource time) {
        this(store, time, PiplexObserver.NONE);
    }

    /**
     * @param store    where the designation, the switch, the milestone and the lease live
     * @param time     where time comes from
     * @param observer told about every decision; this is where the timeline comes from
     */
    public ExclusiveRuns(final CoordinationStore store,
                         final TimeSource time,
                         final PiplexObserver observer) {
        this.store = Objects.requireNonNull(store, "store");
        this.time = Objects.requireNonNull(time, "time");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    /**
     * Asks to run.
     *
     * @param request what is being asked for
     * @return {@link Admitted} while this run owns the work, or why it does not
     */
    public CompletionStage<Admission> begin(final ExclusiveRequest request) {
        return attempt(request, time.deadlineIn(request.handoverWait()));
    }

    static String designationKey(final String key) {
        return Designations.keyOf(key);
    }

    static String switchKey(final String key) {
        return Switches.keyOf(key);
    }

    private static String leaseKey(final String key) {
        return LEASE_PREFIX + key;
    }

    /**
     * The lease is held by a <b>run</b>, not by a deployment. A store is entitled to answer "you already
     * hold this" when the owner matches, and that answer is only safe if the owner names one holder: with
     * the deployment alone, two concurrent runs on the same controller would both be let in, which is
     * exactly what the lease is there to prevent. With the run included, a second run sees a lease held
     * by somebody else, while a retry of the same run after an unknown outcome still recognises its own.
     *
     * @param request the request
     * @return the identity the lease is taken under
     */
    private static String leaseOwner(final ExclusiveRequest request) {
        return request.ownerId() + '/' + request.runId();
    }

    private CompletionStage<Admission> attempt(final ExclusiveRequest request, final long deadlineNanos) {
        return readGuards(request).thenCompose(guards -> {
            final Admission done = alreadyDone(request, guards);
            if (done != null) {
                return CompletableFuture.completedFuture(done);
            }
            return proceed(request, deadlineNanos, guards);
        });
    }

    private Admission alreadyDone(final ExclusiveRequest request, final Guards guards) {
        if (request.completedWhen() == null || !guards.milestone().exists()) {
            return null;
        }
        final Milestone milestone = Milestone.parse(guards.milestone().value());
        if (!milestone.generation().atLeast(request.generation())) {
            return null;
        }
        observer.alreadyCompleted(refOf(request), milestone.generation());
        return new Admission.AlreadyCompleted(milestone.generation());
    }

    /**
     * Reads all three keys an attempt turns on, each with the version it was read at.
     *
     * <p>The versions are the reason they are read together rather than where they are needed: a park
     * waits for one of these keys to move, and "moved since I looked" is only a question you can ask if
     * you kept the answer you looked at.
     *
     * @param request the request
     * @return what all three said
     */
    private CompletionStage<Guards> readGuards(final ExclusiveRequest request) {
        return read(request.designatedBy(), ExclusiveRuns::designationKey).thenCompose(designation ->
                read(request.enabledBy(), ExclusiveRuns::switchKey).thenCompose(state ->
                        read(request.completedWhen(), Milestones::keyOf)
                                .thenApply(milestone -> new Guards(designation, state, milestone))));
    }

    private CompletionStage<Entry> read(final String key, final KeyMapper mapper) {
        if (key == null) {
            return CompletableFuture.completedFuture(
                    Entry.absent(CoordinationStore.INITIAL_VERSION));
        }
        return store.get(mapper.apply(key));
    }

    private CompletionStage<Admission> proceed(final ExclusiveRequest request,
                                               final long deadlineNanos,
                                               final Guards guards) {
        final Switch state = guards.switchEntry().exists()
                ? Switch.parse(guards.switchEntry().value())
                : Switch.ENABLED;
        if (!state.enabled()) {
            observer.disabled(refOf(request), state.reason());
            return CompletableFuture.completedFuture(new Admission.Disabled(state.reason()));
        }
        if (request.designatedBy() != null) {
            final String owner = guards.designation().exists()
                    ? Designation.parse(guards.designation().value()).owner()
                    : null;
            if (!request.ownerId().equals(owner)) {
                return parkOrGiveUp(
                        request, deadlineNanos, guards, new Admission.NotDesignated(owner), null);
            }
        }
        return acquire(request, deadlineNanos, guards);
    }

    private CompletionStage<Admission> acquire(final ExclusiveRequest request,
                                               final long deadlineNanos,
                                               final Guards guards) {
        return store.tryAcquire(leaseKey(request.key()), leaseOwner(request), request.lease())
                .thenCompose(attempt -> {
                    if (attempt instanceof LeaseAttempt.Acquired acquired) {
                        return admit(request, acquired.handle(), guards);
                    }
                    // An acquisition whose outcome was never learnt, retried: success, not contention.
                    if (attempt instanceof LeaseAttempt.HeldBySelf mine) {
                        return admit(request, mine.handle(), guards);
                    }
                    final LeaseAttempt.HeldByOther other = (LeaseAttempt.HeldByOther) attempt;
                    return parkOrGiveUp(request, deadlineNanos, guards,
                            new Admission.HeldByOther(other.ownerId()), other.remaining());
                });
    }

    private CompletionStage<Admission> admit(final ExclusiveRequest request,
                                             final LeaseHandle handle,
                                             final Guards guards) {
        final RunRef ref = refOf(request);
        final HeldAdmission admitted = new HeldAdmission(
                store, request, handle, time, leaseKey(request.key()), observer, ref);
        admitted.start(guards.designation().version(), guards.switchEntry().version());
        observer.admitted(ref, handle.fencingToken());
        return CompletableFuture.completedFuture(admitted);
    }

    private CompletionStage<Admission> parkOrGiveUp(final ExclusiveRequest request,
                                                    final long deadlineNanos,
                                                    final Guards guards,
                                                    final Admission giveUp,
                                                    final Duration leaseLeft) {
        final Duration remaining = time.until(deadlineNanos);
        if (remaining.isZero()) {
            report(request, giveUp);
            return CompletableFuture.completedFuture(giveUp);
        }
        observer.parked(refOf(request), holderOf(giveUp), remaining);
        return park(request, guards, remaining, leaseLeft)
                .thenCompose(ignored -> attempt(request, deadlineNanos));
    }

    /**
     * Waits for something to change, then lets the caller look again.
     *
     * <p>Every wait here is bounded by <b>this round</b> rather than by the whole handover window, and
     * that is the point. A park is a loop of short rounds -- each round re-reads, because a store which
     * coalesces cannot be asked what was missed -- and a watch outliving its round would be joined next
     * round by another, until an hour of waiting means hundreds of outstanding requests against the same
     * three keys, each one still entitled to report what it found. Bounded, a round leaves nothing
     * behind it.
     *
     * <p>Three keys can end the wait, and each is a different answer to "is it my turn yet": the
     * designation moved, the work was switched off, or the milestone says somebody else has finished it.
     * The timer under them is the fourth, and it is how a freed lease gets noticed -- a lease is not a
     * key anybody can watch.
     *
     * @param request   the request
     * @param guards    what the three keys said when they were read
     * @param remaining how much of the handover window is left
     * @param leaseLeft how much of the holder's lease is left, or {@code null} when unknown
     * @return completes when it is worth looking again
     */
    private CompletionStage<Void> park(final ExclusiveRequest request,
                                       final Guards guards,
                                       final Duration remaining,
                                       final Duration leaseLeft) {
        final Duration look = untilNextLook(request, remaining, leaseLeft);
        final CompletableFuture<Void> woken = new CompletableFuture<>();
        watch(request.designatedBy(), ExclusiveRuns::designationKey, guards.designation(), look, woken);
        watch(request.enabledBy(), ExclusiveRuns::switchKey, guards.switchEntry(), look, woken);
        watch(request.completedWhen(), Milestones::keyOf, guards.milestone(), look, woken);
        final TimeSource.Cancellable floor = time.schedule(look, () -> woken.complete(null));
        woken.whenComplete((ignored, error) -> floor.cancel());
        return woken;
    }

    private void watch(final String key,
                       final KeyMapper mapper,
                       final Entry asRead,
                       final Duration within,
                       final CompletableFuture<Void> woken) {
        if (key == null) {
            return;
        }
        store.awaitChange(mapper.apply(key), asRead.version(), within)
                .whenComplete((entry, error) -> woken.complete(null));
    }

    private void report(final ExclusiveRequest request, final Admission giveUp) {
        if (giveUp instanceof Admission.NotDesignated notDesignated) {
            observer.notDesignated(refOf(request), notDesignated.currentOwner());
        } else if (giveUp instanceof Admission.HeldByOther held) {
            observer.heldByOther(refOf(request), held.heldBy());
        }
    }

    private static String holderOf(final Admission giveUp) {
        if (giveUp instanceof Admission.NotDesignated notDesignated) {
            return notDesignated.currentOwner();
        }
        if (giveUp instanceof Admission.HeldByOther held) {
            return held.heldBy();
        }
        return null;
    }

    private static RunRef refOf(final ExclusiveRequest request) {
        return new RunRef(request.key(), request.generation(), request.ownerId(), request.runId());
    }

    /**
     * How long a round of parking lasts.
     *
     * <p>When the holder's lease has longer to run than that, wait for the lease instead: until it
     * lapses, nothing this candidate could read would change the answer. Most stores cannot say how
     * much of somebody else's lease is left -- the figure would have to come off another machine's
     * wall clock -- and then this is simply the renewal interval.
     *
     * @param request   the request
     * @param remaining how much of the handover window is left, which nothing may exceed
     * @param leaseLeft how much of the holder's lease is left, or {@code null} when unknown
     * @return how long to wait before looking again
     */
    private static Duration untilNextLook(final ExclusiveRequest request,
                                          final Duration remaining,
                                          final Duration leaseLeft) {
        Duration wait = request.renewEvery();
        if (leaseLeft != null && leaseLeft.compareTo(wait) > 0) {
            wait = leaseLeft;
        }
        return wait.compareTo(remaining) > 0 ? remaining : wait;
    }

    @FunctionalInterface
    private interface KeyMapper {
        String apply(String key);
    }

    private record Guards(Entry designation, Entry switchEntry, Entry milestone) {
    }
}
