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

        /**
         * The store said the lease is no longer this run's: it lapsed, or somebody else took it.
         *
         * <p>Evidence, and that is what separates it from {@link #RENEWAL_FAILED}. Something was heard
         * back, and what it said was no.
         */
        LEASE_LOST,

        /** The work was switched off underneath the run. */
        DISABLED,

        /**
         * Nothing was heard back from the store before ownership had to be given up.
         *
         * <p>The awkward one: the lease cannot be extended and it cannot be learnt whether anybody else
         * has taken it. Given up at the grace period or at the term of the lease, whichever comes first,
         * because continuing to act as the owner on no evidence is the worse of the two mistakes.
         *
         * <p>Which of those two deadlines arrived first is deliberately not what this is told apart from
         * {@link #LEASE_LOST} on: with the grace period left at its default of one lease they are the
         * same instant, and a run would then report every unreachable store as a lease that ran out.
         * What separates them is whether the store answered at all.
         */
        RENEWAL_FAILED,

        /**
         * A watched designation or switch no longer parses, so what it permits cannot be established.
         *
         * <p>No grace period, unlike {@link #RENEWAL_FAILED}: the same bytes read again say the same
         * thing. {@link Revocation#detail()} names the key somebody has to fix.
         */
        GUARD_UNREADABLE,

        /**
         * A watched designation or switch could not be read at all for as long as that is tolerated.
         *
         * <p>Kept apart from {@link #RENEWAL_FAILED} because the lease is beside the point here: it is
         * being renewed, and that is exactly what makes this worth a reason of its own. One key
         * unreadable while the cluster answers everything else is an ACL somebody narrowed by hand, or
         * one key's requests on a route that is down -- and a run which went on holding the lease
         * through it would be a run nothing was left able to stop. {@link Revocation#detail()} names
         * the key.
         */
        GUARD_UNREACHABLE
    }
}
