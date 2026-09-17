/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.exclusive;

import io.github.green4j.piplex.TimeSource;
import io.github.green4j.piplex.UnreadableKeyException;
import io.github.green4j.piplex.milestone.Milestone;
import io.github.green4j.piplex.milestone.Milestones;
import io.github.green4j.piplex.observe.PiplexObserver;
import io.github.green4j.piplex.observe.RunRef;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.Entry;
import io.github.green4j.piplex.store.FailFastStore;
import io.github.green4j.piplex.store.LeaseAttempt;
import io.github.green4j.piplex.store.LeaseHandle;
import io.github.green4j.piplex.store.Watch;
import io.github.green4j.piplex.switches.Switch;
import io.github.green4j.piplex.switches.Switches;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Acquires and maintains exclusive ownership for elected or designated work.
 *
 * <p>An admitted run holds a renewable lease and watches its designation and switches. A candidate may
 * park for {@code handoverWait} so it can take over after a guard change or lease release.
 */
public final class ExclusiveRuns {

    private static final String LEASE_PREFIX = "piplex/exclusive/";
    // The first look after a key moved; each one after it waits twice as long, up to a whole round.
    private static final Duration HURRY_FROM = Duration.ofMillis(500);

    private final CoordinationStore store;
    private final TimeSource time;
    private final PiplexObserver observer;
    private final Milestones milestones;

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
        this.store = FailFastStore.of(store, time);
        this.time = Objects.requireNonNull(time, "time");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.milestones = new Milestones(this.store, this.time, this.observer);
    }

    /**
     * Asks to run.
     *
     * <p>The answer may be a long time coming: a candidate which is not the one to run parks until
     * something changes or {@code handoverWait} is out, and that is hours by design. Cancel what this
     * returns and the park ends at the next round -- a build stopped while waiting has nobody left to
     * tell, and re-reading three keys on its behalf until the small hours helps no one. A lease taken
     * by a round already in flight is not given back: a retry under the same identity may already have
     * been told it holds it. It lapses within its term unless that retry keeps it.
     *
     * @param request what is being asked for
     * @return {@link Admitted} while this run owns the work, or why it does not
     */
    public CompletionStage<Admission> begin(final ExclusiveRequest request) {
        final Ask ask = new Ask(request);
        final CompletableFuture<Admission> answer = ask.answer;
        attempt(request, time.deadlineIn(request.handoverWait()), ask)
                .whenComplete((admission, error) -> {
                    if (error != null) {
                        answer.completeExceptionally(error);
                    } else if (!answer.complete(admission) && admission instanceof HeldAdmission granted) {
                        granted.abandon();
                    }
                });
        return answer;
    }

    static String designationKey(final String key) {
        return Designations.keyOf(key);
    }

    static String switchKey(final String key) {
        return Switches.keyOf(key);
    }

    // The switch that drains only this owner, or null when the request consults none.
    static String ownSwitch(final ExclusiveRequest request) {
        return request.enabledBy() == null ? null : Switches.ownerKey(request.enabledBy(), request.ownerId());
    }

    // External, so taken as is.
    static String activeKey(final String key) {
        return key;
    }

    static String leaseKey(final String key) {
        return LEASE_PREFIX + key;
    }

    /**
     * The lease is held by a <b>run</b>, not by a deployment. A store is entitled to answer "you already
     * hold this" when the owner matches, and that answer is only safe if the owner names one holder: with
     * the deployment alone, two concurrent runs on the same controller would both be let in, which is
     * exactly what the lease is there to prevent. With the run included, a second run sees a lease held
     * by somebody else, while a retry of the same run after an unknown outcome still recognises its own.
     *
     * <p>One run can ask twice at once, though -- two branches of a parallel stage competing for the
     * same resource is a sensible thing to write, and so is a block nested inside another -- and then
     * the run is not one holder either. Both asks are told "you already hold this", both are let in, and
     * whichever finishes first gives the lease away from under the other. {@code executionId} is what
     * separates them, and it is left out of the identity when a run only ever makes one ask.
     *
     * <p>Each part is escaped before it is joined, because the delimiter is a character the parts are
     * allowed to contain: a {@code runId} is a build's externalizable id, and a build in a folder
     * carries the folder in it. Unescaped, an owner of {@code a} running {@code b/c} and an owner of
     * {@code a/b} running {@code c} are one holder as far as the store is concerned, and both are let
     * in. Nothing ever reads the identity back apart, so there is no decoder to go with this.
     *
     * @param request the request
     * @return the identity the lease is taken under
     */
    static String leaseOwner(final ExclusiveRequest request) {
        final String holder = escaped(request.ownerId()) + '/' + escaped(request.runId());
        return request.executionId() == null ? holder : holder + '/' + escaped(request.executionId());
    }

    // The backslash first, or escaping the delimiter afterwards would escape the escapes as well.
    private static String escaped(final String part) {
        return part.replace("\\", "\\\\").replace("/", "\\/");
    }

    private CompletionStage<Admission> attempt(final ExclusiveRequest request,
                                               final long deadlineNanos,
                                               final Ask ask) {
        if (ask.answer.isDone()) {
            // Whoever asked has gone. A round is where that is noticed, and it is enough: every wait a
            // round takes is bounded by the round, so there is nothing left running behind this.
            return CompletableFuture.failedFuture(
                    new CancellationException("The attempt for '" + request.key() + "' was abandoned"));
        }
        return readGuards(request).thenCompose(guards -> {
            final Admission done = alreadyDone(request, guards.milestone());
            if (done != null) {
                return CompletableFuture.completedFuture(done);
            }
            return proceed(request, deadlineNanos, guards, ask);
        });
    }

    private Admission alreadyDone(final ExclusiveRequest request, final Entry asRead) {
        if (request.completedWhen() == null || !asRead.exists()) {
            return null;
        }
        final Milestone milestone;
        try {
            milestone = Milestone.parse(asRead.value());
        } catch (final RuntimeException notReadable) {
            return unreadable(request, Milestones.keyOf(request.completedWhen()), "milestone", notReadable);
        }
        if (!milestone.generation().atLeast(request.generation())) {
            return null;
        }
        observer.alreadyCompleted(refOf(request), milestone.generation());
        return new Admission.AlreadyCompleted(milestone.generation());
    }

    /**
     * Reads every key an attempt turns on, each with the version it was read at.
     *
     * <p>The versions are the reason they are read together rather than where they are needed: a park
     * waits for one of these keys to move, and "moved since I looked" is only a question you can ask if
     * you kept the answer you looked at.
     *
     * @param request the request
     * @return what they all said
     */
    private CompletionStage<Guards> readGuards(final ExclusiveRequest request) {
        return read(request.designatedBy(), ExclusiveRuns::designationKey).thenCompose(designation ->
                read(request.enabledBy(), ExclusiveRuns::switchKey).thenCompose(state ->
                        read(ownSwitch(request), ExclusiveRuns::switchKey).thenCompose(ownState ->
                                read(request.activeKey(), ExclusiveRuns::activeKey).thenCompose(active ->
                                        read(request.completedWhen(), Milestones::keyOf).thenApply(milestone ->
                                                new Guards(designation, state, ownState, active, milestone))))));
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
                                               final Guards guards,
                                               final Ask ask) {
        final Admission refused = refusal(request, guards);
        if (refused != null) {
            return turnedAway(request, deadlineNanos, guards, refused, ask);
        }
        return acquire(request, deadlineNanos, guards, ask);
    }

    /**
     * What the switch and the designation say about this run.
     *
     * @param request the request
     * @param guards  what the keys said
     * @return why this run may not go on, or {@code null} while it may
     */
    private Admission refusal(final ExclusiveRequest request, final Guards guards) {
        final Admission off = switchedOff(request, request.enabledBy(), guards.switchEntry());
        if (off != null) {
            return off;
        }
        final Admission ownOff = switchedOff(request, ownSwitch(request), guards.ownSwitch());
        if (ownOff != null) {
            return ownOff;
        }
        if (request.activeKey() != null) {
            final String value = guards.active().exists() ? guards.active().value() : null;
            if (!request.activeValue().equals(value)) {
                return new Admission.NotActive(request.activeKey(), value);
            }
        }
        if (request.designatedBy() == null) {
            return null;
        }
        final String owner;
        try {
            owner = guards.designation().exists()
                    ? Designation.parse(guards.designation().value()).owner()
                    : null;
        } catch (final RuntimeException notReadable) {
            return unreadable(request, designationKey(request.designatedBy()), "designation", notReadable);
        }
        return request.ownerId().equals(owner) ? null : new Admission.NotDesignated(owner);
    }

    private Admission switchedOff(final ExclusiveRequest request, final String key, final Entry asRead) {
        final Switch state;
        try {
            state = asRead.exists() ? Switch.parse(asRead.value()) : Switch.ENABLED;
        } catch (final RuntimeException notReadable) {
            return unreadable(request, switchKey(key), "switch", notReadable);
        }
        if (state.enabled()) {
            return null;
        }
        observer.disabled(refOf(request), state.reason());
        return new Admission.Disabled(state.reason());
    }

    // Only a designation or an active key somebody else holds is worth waiting on; the rest are answers.
    private CompletionStage<Admission> turnedAway(final ExclusiveRequest request,
                                                  final long deadlineNanos,
                                                  final Guards guards,
                                                  final Admission refused,
                                                  final Ask ask) {
        if (refused instanceof Admission.NotDesignated || refused instanceof Admission.NotActive) {
            return parkOrGiveUp(request, deadlineNanos, guards, refused, null, ask);
        }
        return CompletableFuture.completedFuture(refused);
    }

    private CompletionStage<Admission> acquire(final ExclusiveRequest request,
                                               final long deadlineNanos,
                                               final Guards guards,
                                               final Ask ask) {
        // Read before the lease is asked for, and it is what every deadline the holder keeps is measured
        // from. The store begins counting the lease down no later than the moment it is asked, so a
        // reading taken afterwards -- after a round trip of unknown length -- would put the expiry later
        // than the store puts it, which is the one direction that is not safe to be wrong in.
        final long askedAtNanos = time.nanos();
        return store.tryAcquire(leaseKey(request.key()), leaseOwner(request), request.lease())
                .thenCompose(attempt -> {
                    if (attempt instanceof LeaseAttempt.Acquired acquired) {
                        return admit(request, deadlineNanos, acquired.handle(),
                                askedAtNanos, acquired.remaining(), ask);
                    }
                    // An acquisition whose outcome was never learnt, retried: success, not contention.
                    // What is left of the lease is a fraction of one, not a fresh one: it has been
                    // running since the attempt which was never answered.
                    if (attempt instanceof LeaseAttempt.HeldBySelf mine) {
                        return admit(request, deadlineNanos, mine.handle(),
                                askedAtNanos, mine.remaining(), ask);
                    }
                    if (attempt instanceof LeaseAttempt.Contended) {
                        return parkOrGiveUp(request, deadlineNanos, guards,
                                new Admission.Contended(), null, ask);
                    }
                    final LeaseAttempt.HeldByOther other = (LeaseAttempt.HeldByOther) attempt;
                    return parkOrGiveUp(request, deadlineNanos, guards,
                            new Admission.HeldByOther(other.ownerId()), other.remaining(), ask);
                });
    }

    /**
     * Looks at all three keys once more, now that the lease is in hand.
     *
     * <p>The reads before the lease are a guess. A holder can finish the work, publish, and give the
     * lease up between them and this acquisition, and the candidate would redo a day that is already
     * done. A designation can move and a switch can be turned off in the same gap, and the run would
     * start work it no longer owns and learn so only a watch later. These reads catch all of that, and
     * the watches are armed on what they said, so a change after them is one the watches answer.
     *
     * <p>It is not a guarantee that the milestone will not move, and the lease does not make it one. A
     * milestone is an ordinary key, written by whoever calls {@link Milestones#publish}, and nothing in
     * the store ties that write to this lease. What the two reads together rest on is the caller's own
     * order: <b>publish while the admission is still held</b>. A run which releases first and publishes
     * afterwards leaves a gap in which both of these reads say the work is undone, and the next
     * candidate is admitted to do it again -- see {@link ExclusiveRequest#completedWhen()}.
     *
     * @param request         the request
     * @param deadlineNanos   when the handover window is out
     * @param handle          the lease just taken
     * @param acquiredAtNanos the reading taken before the lease was asked for
     * @param remaining       how long the store says the lease lasts unless renewed
     * @param ask             what the caller is waiting on
     * @return the admission, or why this run does not get one
     */
    private CompletionStage<Admission> admit(final ExclusiveRequest request,
                                             final long deadlineNanos,
                                             final LeaseHandle handle,
                                             final long acquiredAtNanos,
                                             final Duration remaining,
                                             final Ask ask) {
        // Before the reads are sent, so a guard's grace is never counted from later than what it learnt.
        final long readAtNanos = time.nanos();
        return readGuards(request)
                // The lease is held by the time these reads are made, so a read that fails is a lease
                // nobody can use and nobody is watching: given back here rather than left to lapse,
                // which is a full lease in which nothing is admitted for a read that did not land.
                // On the reads alone rather than on the whole chain: granted() gives the lease back
                // itself, and a handler over both would give it back a second time.
                //
                // A release that fails as well rides along on the read's failure rather than replacing
                // it: the read is why this run was not admitted, and the release only says the lease
                // may stand until it lapses.
                .exceptionallyCompose(unread -> this.<Guards>releasedAfter(request, handle, unread))
                .thenCompose(fresh -> {
                    final Admission done;
                    final Admission refused;
                    try {
                        done = alreadyDone(request, fresh.milestone());
                        refused = done == null ? refusal(request, fresh) : null;
                    } catch (final RuntimeException thrown) {
                        // An observer threw while being told why: the caller gets the throw, so the
                        // lease goes back first, as in granted().
                        return releasedAfter(request, handle, thrown);
                    }
                    if (done != null) {
                        return release(request, handle).thenApply(ignored -> done);
                    }
                    if (refused != null) {
                        return release(request, handle).thenCompose(ignored ->
                                turnedAway(request, deadlineNanos, fresh, refused, ask));
                    }
                    if (ask.answer.isDone()) {
                        // Nobody is waiting for this admission; see begin() for why the lease stays.
                        return CompletableFuture.failedFuture(new CancellationException(
                                "The attempt for '" + request.key() + "' was abandoned"));
                    }
                    if (!termOutlastsTheReads(request, acquiredAtNanos, remaining, readAtNanos)) {
                        // The reads took the term: a renewal sent now would land after the lease is
                        // somebody else's to take. Nobody is running the work, so asking again it is.
                        // A failed release is not worth reporting: the lease is within a read of its end.
                        return release(request, handle)
                                .handle((ignored, unreleased) -> null)
                                .thenCompose(ignored -> parkOrGiveUp(request, deadlineNanos, fresh,
                                        new Admission.Contended(), null, ask));
                    }
                    return granted(request, handle, fresh, acquiredAtNanos, remaining, readAtNanos);
                });
    }

    /**
     * Gives the lease back, then fails with what made it necessary.
     *
     * @param request the request
     * @param handle  the lease to give back
     * @param cause   why this run is not admitted
     * @param <T>     the stage's type
     * @return fails with {@code cause}, a failed release suppressed in it
     */
    private <T> CompletionStage<T> releasedAfter(final ExclusiveRequest request,
                                                 final LeaseHandle handle,
                                                 final Throwable cause) {
        return release(request, handle)
                .handle((ignored, unreleased) -> {
                    if (unreleased != null) {
                        HeldAdmission.unwrapped(cause).addSuppressed(HeldAdmission.unwrapped(unreleased));
                    }
                    return CompletableFuture.<T>failedFuture(cause);
                })
                .thenCompose(failed -> failed);
    }

    /**
     * Whether the term has at least as long left as the reads just took, so a renewal of the same
     * latency can still land inside it. {@code responseBound} is a bound on a hang, not a latency,
     * and is often longer than the lease itself.
     *
     * @param request         the request
     * @param acquiredAtNanos the reading taken before the lease was asked for
     * @param remaining       how long the store says the lease lasts unless renewed
     * @param readAtNanos     the reading taken before the reads were sent
     * @return whether the admission may be handed over
     */
    private boolean termOutlastsTheReads(final ExclusiveRequest request,
                                         final long acquiredAtNanos,
                                         final Duration remaining,
                                         final long readAtNanos) {
        final long termEndsNanos = acquiredAtNanos + HeldAdmission.termOf(remaining, request.lease()).toNanos();
        final long nowNanos = time.nanos();
        final long leftNanos = termEndsNanos - nowNanos;
        return leftNanos > 0L && leftNanos >= nowNanos - readAtNanos;
    }

    private CompletionStage<Void> release(final ExclusiveRequest request, final LeaseHandle handle) {
        return store.release(leaseKey(request.key()), handle);
    }

    private CompletionStage<Admission> granted(final ExclusiveRequest request,
                                               final LeaseHandle handle,
                                               final Guards guards,
                                               final long acquiredAtNanos,
                                               final Duration remaining,
                                               final long guardsReadAtNanos) {
        final RunRef ref = refOf(request);
        final HeldAdmission admitted = new HeldAdmission(
                store, milestones, request, handle, time, leaseKey(request.key()), observer, ref,
                acquiredAtNanos, remaining, guardsReadAtNanos);
        try {
            // Told before arming: a watch which answers at once revokes from inside start(), and a log
            // must not say a run lost what it had not yet been said to have.
            observer.admitted(ref, handle.fencingToken());
            // Arming is in here too: a scheduler that has been shut down refuses the renewal timer,
            // and an admission half armed is one nothing renews and nothing watches.
            //
            // Armed on the versions read with the lease in hand: a designation that moved since is a
            // key already past the version being watched from, so the watch answers rather than waits.
            admitted.start(guards.designation().version(), guards.switchEntry().version(),
                    guards.ownSwitch().version(), guards.active().version());
        } catch (final RuntimeException thrown) {
            // The caller is about to be given the throw instead of the admission, and a lease whose
            // handle nobody holds is renewed until the controller stops. Given back first.
            admitted.release();
            throw thrown;
        }
        return CompletableFuture.completedFuture(admitted);
    }

    private CompletionStage<Admission> parkOrGiveUp(final ExclusiveRequest request,
                                                    final long deadlineNanos,
                                                    final Guards guards,
                                                    final Admission giveUp,
                                                    final Duration leaseLeft,
                                                    final Ask ask) {
        final Duration remaining = time.until(deadlineNanos);
        if (remaining.isZero()) {
            report(request, giveUp);
            return CompletableFuture.completedFuture(giveUp);
        }
        observer.parked(refOf(request), holderOf(giveUp), remaining);
        // Only the lease has no watch to wake this candidate, so only a wait for the lease hurries.
        final boolean forTheLease = giveUp instanceof Admission.HeldByOther
                || giveUp instanceof Admission.Contended;
        final Duration hurried = ask.hurried(guards, request.renewEvery(), forTheLease);
        return park(ask, request, guards, untilNextLook(request, remaining, leaseLeft, hurried))
                .thenCompose(ignored -> attempt(request, deadlineNanos, ask));
    }

    /**
     * Waits for something to change, then lets the caller look again.
     *
     * <p>Every wait here is bounded by <b>this round</b> rather than by the whole handover window. A
     * park is a loop of short rounds -- each round re-reads, because a store which coalesces cannot be
     * asked what was missed. A round woken early by one key leaves the watches on the other two out,
     * and the next round joins them rather than adding its own: see {@link StandingWatch}.
     *
     * <p>The keys that can end the wait are each a different answer to "is it my turn yet": the
     * designation or the active key moved, the work was switched off -- everywhere or here -- or the milestone says
     * somebody else has finished it.
     * The timer under them is the fourth, and it is how a freed lease gets noticed -- a lease is not a
     * key anybody can watch.
     *
     * @param ask     the ask the watches are kept on
     * @param request the request
     * @param guards  what the keys said when they were read
     * @param look    how long the round lasts
     * @return completes when it is worth looking again
     */
    private CompletionStage<Void> park(final Ask ask,
                                          final ExclusiveRequest request,
                                          final Guards guards,
                                          final Duration look) {
        final long endsAtNanos = time.deadlineIn(look);
        final CompletableFuture<Void> woken = new CompletableFuture<>();
        watch(ask.designation, guards.designation(), endsAtNanos, woken);
        watch(ask.switchEntry, guards.switchEntry(), endsAtNanos, woken);
        watch(ask.ownSwitch, guards.ownSwitch(), endsAtNanos, woken);
        watch(ask.active, guards.active(), endsAtNanos, woken);
        watch(ask.milestone, guards.milestone(), endsAtNanos, woken);
        final TimeSource.Cancellable floor = time.schedule(look, () -> woken.complete(null));
        woken.whenComplete((ignored, error) -> floor.cancel());
        return woken;
    }

    private static void watch(final StandingWatch watch,
                              final Entry asRead,
                              final long roundEndsNanos,
                              final CompletableFuture<Void> woken) {
        if (watch != null) {
            watch.join(asRead, roundEndsNanos, woken);
        }
    }

    /**
     * Gives up on a key nobody can read, rather than failing with whatever the parser threw.
     *
     * <p>Never parked on, unlike contention: the same bytes read again say the same thing, so the wait
     * would be for a change nobody is making. Somebody has to fix the key.
     *
     * <p>This is {@link Revocation.Reason#GUARD_UNREADABLE} on the other side of admission, and it says
     * the same sentence -- a hand-edited value is one operator error, and which side of the admission it
     * was found on is not what the person fixing it needs to be told.
     *
     * @param request     the request
     * @param key         the key which could not be read
     * @param what        what that key is
     * @param notReadable why it could not be read
     * @return why this run may not proceed
     */
    private Admission unreadable(final ExclusiveRequest request,
                                 final String key,
                                 final String what,
                                 final RuntimeException notReadable) {
        final String detail = unreadableDetail(key, what, notReadable);
        observer.guardUnreadable(refOf(request), key, detail);
        return new Admission.GuardUnreadable(key, detail);
    }

    static String unreadableDetail(final String key,
                                   final String what,
                                   final RuntimeException notReadable) {
        return UnreadableKeyException.detail(key, what, notReadable);
    }

    private void report(final ExclusiveRequest request, final Admission giveUp) {
        if (giveUp instanceof Admission.NotDesignated notDesignated) {
            observer.notDesignated(refOf(request), notDesignated.currentOwner());
        } else if (giveUp instanceof Admission.NotActive notActive) {
            observer.notActive(refOf(request), notActive.key(), notActive.currentValue());
        } else if (giveUp instanceof Admission.HeldByOther held) {
            observer.heldByOther(refOf(request), held.heldBy());
        } else if (giveUp instanceof Admission.Contended) {
            observer.contended(refOf(request));
        }
    }

    private static String holderOf(final Admission giveUp) {
        if (giveUp instanceof Admission.NotDesignated notDesignated) {
            return notDesignated.currentOwner();
        }
        if (giveUp instanceof Admission.NotActive notActive) {
            return notActive.currentValue();
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
     * <p>Neither applies in a hurry: right after a key moved, the holder has most likely just been told
     * to stop, and gives the lease back as soon as it has, well before the lease would lapse.
     *
     * @param request   the request
     * @param remaining how much of the handover window is left, which nothing may exceed
     * @param leaseLeft how much of the holder's lease is left, or {@code null} when unknown
     * @param hurried   the short wait to take instead, or {@code null} when not in a hurry
     * @return how long to wait before looking again
     */
    private static Duration untilNextLook(final ExclusiveRequest request,
                                          final Duration remaining,
                                          final Duration leaseLeft,
                                          final Duration hurried) {
        Duration wait = request.renewEvery();
        if (hurried != null) {
            wait = hurried;
        } else if (leaseLeft != null && leaseLeft.compareTo(wait) > 0) {
            wait = leaseLeft;
        }
        return wait.compareTo(remaining) > 0 ? remaining : wait;
    }

    /**
     * One call to {@link #begin}: what its caller waits on, and the watches its rounds of parking share.
     */
    private final class Ask {

        private final CompletableFuture<Admission> answer = new CompletableFuture<>();
        private final StandingWatch designation;
        private final StandingWatch switchEntry;
        private final StandingWatch ownSwitch;
        private final StandingWatch active;
        private final StandingWatch milestone;
        // What the keys said at the last round, or null before the first. Guarded by this.
        private Guards seen;
        // The next short wait while hurrying, or null. Guarded by this.
        private Duration hurry;

        private Ask(final ExclusiveRequest request) {
            designation = standing(request.designatedBy(), ExclusiveRuns::designationKey);
            switchEntry = standing(request.enabledBy(), ExclusiveRuns::switchKey);
            ownSwitch = standing(ownSwitch(request), ExclusiveRuns::switchKey);
            active = standing(request.activeKey(), ExclusiveRuns::activeKey);
            milestone = standing(request.completedWhen(), Milestones::keyOf);
        }

        private StandingWatch standing(final String key, final KeyMapper mapper) {
            return key == null ? null : new StandingWatch(mapper.apply(key));
        }

        /**
         * The next short wait, doubling each time, until it would be no shorter than a round.
         *
         * <p>Hurrying starts when a key moved since the last round, whichever way that was noticed: a
         * holder told to stop gives the lease back once it has.
         *
         * @param guards      what the keys say now
         * @param round       how long a round lasts otherwise
         * @param forTheLease whether the wait is for the lease, the one thing no watch reports
         * @return the wait to take, or {@code null} when not in a hurry
         */
        private synchronized Duration hurried(final Guards guards,
                                              final Duration round,
                                              final boolean forTheLease) {
            if (seen != null && seen.movedTo(guards)) {
                hurry = HURRY_FROM;
            }
            seen = guards;
            final Duration now = hurry;
            if (now == null || !forTheLease || now.compareTo(round) >= 0) {
                hurry = null;
                return null;
            }
            hurry = now.multipliedBy(2);
            return now;
        }
    }

    /**
     * The watch on one key, kept across rounds of parking.
     *
     * <p>A watch cannot be called off, so a round that ends before its watches do would leave them
     * behind, and the next round would add its own. Kept here instead, a round joins the watch already
     * out when it waits past the same version, and there is never more than one per key.
     */
    private final class StandingWatch {

        private final String key;
        // The version the watch that is out waits past, or null when none is.
        private String waitingPast;
        private CompletableFuture<Void> woken;
        private long roundEndsNanos;
        // When the watch that is out was asked to answer by.
        private long armedUntilNanos;

        private StandingWatch(final String key) {
            this.key = key;
        }

        private void join(final Entry asRead, final long roundEndsNanos, final CompletableFuture<Void> woken) {
            final String since = asRead.version();
            synchronized (this) {
                this.woken = woken;
                this.roundEndsNanos = roundEndsNanos;
                if (since.equals(waitingPast)) {
                    return;
                }
                waitingPast = since;
                armedUntilNanos = roundEndsNanos;
            }
            arm(since, time.until(roundEndsNanos));
        }

        private void arm(final String since, final Duration within) {
            store.awaitChange(key, since, within, Watch.BACKGROUND)
                    .whenComplete((entry, error) -> answered(since, entry, error));
        }

        private void answered(final String since, final Entry entry, final Throwable error) {
            // A watch that failed is not a change, and waking on it would re-read at once and watch again
            // at once. What is left to end the round is its timer.
            //
            // Whether the answer was confirmed is deliberately not asked: all a round wants to know is
            // when it is worth looking again, and it re-reads every key when it does.
            final boolean changed = error == null && !entry.version().equals(since);
            final CompletableFuture<Void> wake;
            final Duration rest;
            final boolean again;
            synchronized (this) {
                if (!since.equals(waitingPast)) {
                    return;
                }
                wake = woken;
                rest = time.until(roundEndsNanos);
                // Unchanged, and out of a round that ends later than it was armed for: kept for the rest.
                again = !changed && error == null && !wake.isDone() && !rest.isZero()
                        && roundEndsNanos - armedUntilNanos > 0L;
                if (again) {
                    armedUntilNanos = roundEndsNanos;
                } else {
                    waitingPast = null;
                }
            }
            // Outside the monitor: the next round starts in here, and joins the other keys' watches.
            if (changed) {
                wake.complete(null);
            } else if (again) {
                arm(since, rest);
            }
        }
    }

    @FunctionalInterface
    private interface KeyMapper {
        String apply(String key);
    }

    private record Guards(Entry designation, Entry switchEntry, Entry ownSwitch, Entry active, Entry milestone) {

        private boolean movedTo(final Guards now) {
            return !designation.version().equals(now.designation.version())
                    || !switchEntry.version().equals(now.switchEntry.version())
                    || !ownSwitch.version().equals(now.ownSwitch.version())
                    || !active.version().equals(now.active.version())
                    || !milestone.version().equals(now.milestone.version());
        }
    }
}
