/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.store;

import java.time.Duration;

/**
 * The outcome of asking for a lease.
 *
 * <p>{@link HeldBySelf} is kept apart from {@link HeldByOther} on purpose: a store need not be
 * reentrant, so an acquisition whose outcome was never learned -- a timeout, an unreachable cluster --
 * is retried and comes back as "you already hold this", which is success, not contention.
 *
 * <p>What is left of a lease is reported as a <b>duration</b> rather than as a point in time. A point in
 * time would have to be expressed on somebody's wall clock, and the only clocks involved -- ours, the
 * store's, the other holder's -- are three different opinions. A duration survives the trip: the caller
 * turns it into a deadline against its own monotonic reading, and nothing depends on the three agreeing.
 */
public sealed interface LeaseAttempt {

    /**
     * The lease was taken by this caller.
     *
     * @param handle    what to present to renew or release it
     * @param remaining how long it lasts unless renewed
     */
    record Acquired(LeaseHandle handle, Duration remaining) implements LeaseAttempt {
    }

    /**
     * Somebody else holds the lease.
     *
     * <p>How much of that lease is left is reported only by a store which can measure it the way every
     * period here is measured -- monotonically, in the process doing the measuring. A store whose only
     * record of another holder's lease is a deadline on somebody else's wall clock says {@code null}
     * instead of subtracting one clock from another, and a candidate then looks again on its own
     * interval. Where it is reported it is still a hint: it decides when it is worth looking again, and
     * never whether somebody still owns something.
     *
     * @param ownerId   who holds it; never {@code null} -- a lease nobody holds is {@link Contended}
     * @param remaining how much of their lease is left, or {@code null} when the store cannot say
     */
    record HeldByOther(String ownerId, Duration remaining) implements LeaseAttempt {
    }

    /**
     * Nobody holds the lease, and this attempt still did not take it: somebody else's write got there
     * first and left it free again. Neither a grant nor a rival -- look again, there is nobody to wait
     * for.
     */
    record Contended() implements LeaseAttempt {
    }

    /**
     * This caller already holds the lease, from an earlier attempt whose outcome was not learned.
     *
     * @param handle    what to present to renew or release it
     * @param remaining how long it lasts unless renewed
     */
    record HeldBySelf(LeaseHandle handle, Duration remaining) implements LeaseAttempt {
    }
}
