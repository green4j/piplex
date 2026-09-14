/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.observe;

import io.github.green4j.piplex.Generation;
import io.github.green4j.piplex.exclusive.Revocation;

import java.time.Duration;

/**
 * Everything piplex decides, offered as a closed vocabulary.
 *
 * <p>piplex keeps no history of its own and never will: the store underneath holds current state, has
 * no change feed, and coalesces. History therefore lives where the runs do -- in build history and in
 * whatever aggregates their logs -- and this interface is the contract that makes such aggregation
 * possible across controllers which cannot see each other.
 *
 * <p>Two things are worth knowing about what it can and cannot tell you. It is complete about
 * <b>decisions</b>: a candidate which is not the owner parks rather than exits, so every controller
 * produces a line on every attempt and there are no silent non-decisions to infer. It is <b>not</b> an
 * audit trail: these events are written by the same process they describe. Who changed a designation,
 * and on whose authority, has to come from the operation that changed it.
 *
 * <p>Every method has a do-nothing default, so an implementation names only what it cares about.
 * Implementations are constructed explicitly and handed in.
 */
public interface PiplexObserver {

    /** Observes nothing. */
    PiplexObserver NONE = new PiplexObserver() {
    };

    /**
     * This run owns the work.
     *
     * @param run          which run
     * @param fencingToken the token to pass to whatever it protects
     */
    default void admitted(RunRef run, long fencingToken) {
    }

    /**
     * Somebody else is designated.
     *
     * @param run          which run
     * @param currentOwner who is, or {@code null} when nobody is
     */
    default void notDesignated(RunRef run, String currentOwner) {
    }

    /**
     * A run is already under way.
     *
     * @param run    which run asked
     * @param heldBy which run holds it
     */
    default void heldByOther(RunRef run, String heldBy) {
    }

    /**
     * The work for this generation is already done.
     *
     * @param run     which run asked
     * @param reached how far the milestone got
     */
    default void alreadyCompleted(RunRef run, Generation reached) {
    }

    /**
     * The work is switched off.
     *
     * @param run    which run asked
     * @param reason why it is off, where one was given
     */
    default void disabled(RunRef run, String reason) {
    }

    /**
     * The run is waiting rather than giving up, in case it is asked to take over.
     *
     * @param run       which run
     * @param waitingOn who currently owns the work, or {@code null}
     * @param remaining how long it will wait
     */
    default void parked(RunRef run, String waitingOn, Duration remaining) {
    }

    /**
     * Ownership was lost while the run was under way.
     *
     * @param run   which run
     * @param cause why
     */
    default void revoked(RunRef run, Revocation cause) {
    }

    /**
     * The run gave the work up of its own accord.
     *
     * @param run which run
     */
    default void released(RunRef run) {
    }

    /**
     * A milestone moved forward.
     *
     * <p>Which milestone and how far are the {@code key} and {@code generation} the reference already
     * carries; a milestone is what is being competed for here, in the same way a lease key is
     * elsewhere.
     *
     * @param run which run published it, and what it published
     */
    default void milestonePublished(RunRef run) {
    }

    /**
     * A wait ended because the milestone got far enough.
     *
     * @param milestone the milestone key
     * @param reached   how far it got
     */
    default void milestoneReached(String milestone, Generation reached) {
    }

    /**
     * A wait has begun and is going to last a while.
     *
     * <p>Said once, when the first read comes up short, and not on every look afterwards. Without it an
     * hour of waiting is an hour of silence, and silence is the one thing an operator cannot tell apart
     * from a hung build.
     *
     * @param milestone  what is being waited for
     * @param wanted     the generation needed
     * @param reached    what is published now, or {@code null} when nothing is
     * @param giveUpAfter how long the wait will last at most
     */
    default void milestoneWaiting(String milestone, Generation wanted, Generation reached,
                                  Duration giveUpAfter) {
    }

    /**
     * A wait ended without the milestone getting far enough.
     *
     * @param milestone the milestone key
     * @param wanted    what was needed
     * @param reached   how far it had got, or {@code null} when nothing was ever published
     */
    default void milestoneTimedOut(String milestone, Generation wanted, Generation reached) {
    }

}
