/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.exclusive;

import io.github.green4j.piplex.Generation;

/**
 * Whether this run may proceed, and if not, why not.
 *
 * <p>Every outcome but {@link Admitted} means "do not run", and they are kept apart because they mean
 * different things to whoever is reading the log: three of them are normal, one is contention, and one
 * is somebody's mistake in a key.
 */
public sealed interface Admission
        permits Admitted, Admission.NotDesignated, Admission.HeldByOther, Admission.Contended,
                Admission.AlreadyCompleted, Admission.Disabled, Admission.GuardUnreadable {

    /**
     * Somebody else is the designated owner. Normal on every controller but one, and not a failure.
     *
     * @param currentOwner who is designated, or {@code null} when nobody is
     */
    record NotDesignated(String currentOwner) implements Admission {
    }

    /**
     * A run is already under way. Reported whether or not a designation is in play: the designated
     * owner is still not allowed to run twice at once.
     *
     * @param heldBy which run holds the lease, as {@code ownerId/runId} -- an operator wants both,
     *               and "another run on this very controller" is a different problem from "another
     *               controller has it"
     */
    record HeldByOther(String heldBy) implements Admission {
    }

    /**
     * Nobody holds the lease, and taking it still lost to another attempt's write. Nobody is running
     * the work; asking again is the answer.
     */
    record Contended() implements Admission {
    }

    /**
     * The work for this generation is already done, so there is nothing to do.
     *
     * @param reached the generation the milestone has got to
     */
    record AlreadyCompleted(Generation reached) implements Admission {
    }

    /**
     * The work is switched off.
     *
     * @param reason why, where whoever switched it off said; {@code null} otherwise
     */
    record Disabled(String reason) implements Admission {
    }

    /**
     * A key this attempt turns on no longer parses, so what it permits cannot be established.
     *
     * <p>The counterpart of {@link Revocation.Reason#GUARD_UNREADABLE} for a run which was never
     * admitted. A hand-edited value is the same operator error either side of admission, and it should
     * read as one rather than as whatever exception the parser threw.
     *
     * @param key    the key which could not be read
     * @param detail what is wrong with it
     */
    record GuardUnreadable(String key, String detail) implements Admission {
    }
}
