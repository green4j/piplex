/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.exclusive;

import io.github.green4j.piplex.TimeSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A clock which keeps what was cancelled, so that a test can run a timer that was called off too late.
 *
 * <p>That is not a contrived state. Cancelling a scheduled action does not stop one which has already
 * begun -- {@code ScheduledFuture.cancel(false)} says so -- so an action can run after the code which
 * cancelled it has gone on to do the very thing the action exists to prevent. A manual clock cannot
 * produce that on its own: it takes an action out of its queue and runs it, and a cancel in between is
 * a removal from a queue the action is no longer in.
 *
 * <p>Constructed explicitly around another {@link TimeSource}, like every implementation here.
 */
final class LateTimers implements TimeSource {

    private final TimeSource delegate;
    private final Deque<Runnable> cancelled = new ArrayDeque<>();

    LateTimers(final TimeSource delegate) {
        this.delegate = delegate;
    }

    /**
     * Runs the action cancelled most recently, as a scheduler which was a moment too late would have.
     */
    void runTheTimerThatWasTooLateToCancel() {
        final Runnable late = cancelled.pollLast();
        if (late == null) {
            throw new AssertionError("Nothing was cancelled, so there is no late timer to run");
        }
        late.run();
    }

    @Override
    public long nanos() {
        return delegate.nanos();
    }

    @Override
    public Instant wallTime() {
        return delegate.wallTime();
    }

    @Override
    public Cancellable schedule(final Duration delay, final Runnable action) {
        final Cancellable scheduled = delegate.schedule(delay, action);
        return () -> {
            cancelled.addLast(action);
            scheduled.cancel();
        };
    }
}
