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
 * Observer for piplex's fixed decision vocabulary.
 *
 * <p>Callbacks may run on caller, store or timer threads and must return quickly. Calls for different
 * runs may overlap; calls for one run are ordered and made without piplex locks. Every method defaults
 * to no action. Events support operational history but are not an authenticated audit trail.
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
     * The external active key does not hold the value this run needs.
     *
     * @param run          which run
     * @param key          the key consulted
     * @param currentValue what it holds, or {@code null} when it holds nothing
     */
    default void notActive(RunRef run, String key, String currentValue) {
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
     * Nobody holds the lease, and taking it lost to another attempt's write.
     *
     * @param run which run asked
     */
    default void contended(RunRef run) {
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
     * A key an attempt turns on could not be read, so the run was not admitted.
     *
     * <p>Separate from {@link #revoked} because nothing was ever held: the same bad bytes, found before
     * the work started rather than under way.
     *
     * @param run    which run asked
     * @param key    the key which could not be read
     * @param detail what is wrong with it
     */
    default void guardUnreadable(RunRef run, String key, String detail) {
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

    /**
     * Somebody else may run the work from now on.
     *
     * <p>Said by the process that made the change, so it is as trustworthy as that process: the log
     * of an authenticated job, not an audit trail.
     *
     * @param key      what is competed for
     * @param owner    who is designated now
     * @param previous who was, or {@code null} when nobody was or the value did not parse
     * @param reason   why, may be {@code null}
     */
    default void designated(String key, String owner, String previous, String reason) {
    }

    /**
     * A switch was flipped.
     *
     * @param key     the switch
     * @param enabled whether the work may run now
     * @param reason  why, may be {@code null}
     */
    default void switched(String key, boolean enabled, String reason) {
    }
}
