/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.exclusive;

/**
 * Why a run which was admitted is no longer allowed to continue.
 *
 * @param reason   what happened
 * @param newOwner who holds it now, where that is known; {@code null} otherwise
 * @param detail   what somebody would have to be told to act on it, or {@code null} when the reason
 *                 says all there is to say
 */
public record Revocation(Reason reason, String newOwner, String detail) {

    /**
     * @param reason   what happened
     * @param newOwner who holds it now, where that is known; {@code null} otherwise
     */
    public Revocation(final Reason reason, final String newOwner) {
        this(reason, newOwner, null);
    }

    /**
     * The ways ownership is lost. They are kept apart because an operator reading the log needs to tell
     * a deliberate handover from a fault, and because only one of them is ambiguous.
     */
    public enum Reason {

        /** An operator named somebody else. Planned, and the usual case. */
        DESIGNATION_CHANGED,

        /** The lease lapsed or was taken while this run still believed it held it. */
        LEASE_LOST,

        /** The work was switched off underneath the run. */
        DISABLED,

        /**
         * The store could not be reached to renew.
         *
         * <p>The awkward one: the lease cannot be extended and it cannot be learnt whether anybody else
         * has taken it. Treated as lost once the grace period is out, because continuing to act as the
         * owner on no evidence is the worse of the two mistakes.
         */
        RENEWAL_FAILED,

        /**
         * A watched designation or switch no longer parses, so what it permits cannot be established.
         *
         * <p>No grace period, unlike {@link #RENEWAL_FAILED}: the same bytes read again say the same
         * thing. {@link Revocation#detail()} names the key somebody has to fix.
         */
        GUARD_UNREADABLE
    }
}
