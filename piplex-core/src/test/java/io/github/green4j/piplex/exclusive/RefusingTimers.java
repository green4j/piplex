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
import java.util.concurrent.RejectedExecutionException;

/**
 * A clock whose scheduler stops taking work at a chosen moment, which is what a controller shutting
 * down does to a run in flight: {@code ScheduledExecutorService.schedule} throws
 * {@link RejectedExecutionException} once it is shut down, and {@link TimeSource#of} passes that
 * straight through.
 *
 * <p>It is the one failure the timers here cannot absorb by trying again, because trying again is
 * itself a {@code schedule}. What a test wants to know is which side of it the run ends up on: a run
 * left with no timer at all is one holding a lease that nothing will ever revoke.
 *
 * <p>Constructed explicitly around another {@link TimeSource}, like every implementation here.
 */
final class RefusingTimers implements TimeSource {

    private final TimeSource delegate;
    private volatile boolean taking = true;

    RefusingTimers(final TimeSource delegate) {
        this.delegate = delegate;
    }

    /**
     * Refuses this and every later {@code schedule}, as a scheduler which has been shut down does.
     */
    void shutDown() {
        taking = false;
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
        if (!taking) {
            throw new RejectedExecutionException("The scheduler has been shut down");
        }
        return delegate.schedule(delay, action);
    }
}
