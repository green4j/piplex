/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Monotonic elapsed time, scheduling and human-readable wall time.
 *
 * <p>All deadlines use {@link #nanos()}; {@link #wallTime()} only stamps records. Monotonic readings are
 * process-local, so stores report remaining lease durations rather than clock values.
 */
public interface TimeSource {

    /**
     * A reading of the monotonic clock. Only differences between readings mean anything.
     *
     * @return nanoseconds since an arbitrary origin, never going backwards
     */
    long nanos();

    /**
     * What time it is, for writing into a record a person will read.
     *
     * <p>Never for timing: it may step backwards or forwards at any moment, and it is not the same as
     * any other machine's.
     *
     * @return the wall-clock reading
     */
    Instant wallTime();

    /**
     * Schedules an action.
     *
     * @param delay  how long to wait
     * @param action what to run
     * @return a handle which cancels the action if it has not run yet
     */
    Cancellable schedule(Duration delay, Runnable action);

    /**
     * @param delay how far ahead
     * @return the reading that much time from now, for comparing with {@link #deadlinePassed(long)}
     */
    default long deadlineIn(final Duration delay) {
        return nanos() + delay.toNanos();
    }

    /**
     * Whether a deadline has been reached.
     *
     * <p>Compared by subtraction rather than by {@code <}, so that it stays right when the readings
     * straddle the point where a {@code long} of nanoseconds wraps.
     *
     * @param deadlineNanos a reading from {@link #deadlineIn(Duration)}
     * @return whether it has been reached
     */
    default boolean deadlinePassed(final long deadlineNanos) {
        return deadlineNanos - nanos() <= 0L;
    }

    /**
     * @param deadlineNanos a reading from {@link #deadlineIn(Duration)}
     * @return how long until it, never negative
     */
    default Duration until(final long deadlineNanos) {
        final long remaining = deadlineNanos - nanos();
        return remaining <= 0L ? Duration.ZERO : Duration.ofNanos(remaining);
    }

    /**
     * A scheduled action which has not necessarily run yet.
     */
    @FunctionalInterface
    interface Cancellable {

        /**
         * Cancels the action if it is still pending. Cancelling twice, or after it has run, does nothing.
         */
        void cancel();
    }

    /**
     * The system clocks and a scheduler, for production use.
     *
     * <p>Timers here are cancelled all the time, so give it a {@code ScheduledThreadPoolExecutor} with
     * {@code setRemoveOnCancelPolicy(true)}: otherwise a cancelled one is kept until it would have fired.
     *
     * @param executor where scheduled actions run; its lifecycle stays with the caller
     * @return the time source
     */
    static TimeSource of(final ScheduledExecutorService executor) {
        return new TimeSource() {

            @Override
            public long nanos() {
                return System.nanoTime();
            }

            @Override
            public Instant wallTime() {
                return Instant.now();
            }

            @Override
            public Cancellable schedule(final Duration delay, final Runnable action) {
                final var future = executor.schedule(action, delay.toNanos(), TimeUnit.NANOSECONDS);
                return () -> future.cancel(false);
            }
        };
    }
}
