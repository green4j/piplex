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
 * Where time comes from: how long since, how long until, when to run something, and what to stamp on a
 * record.
 *
 * <p>Every duration piplex measures is measured here, and nothing measures durations with a wall clock.
 * A wall clock is a statement about what time it is, and it is allowed to change: NTP steps it,
 * an operator sets it, a suspended VM resumes with hours missing. A lease timed against one can lapse
 * early -- two runs at once -- or late, and a handover can wait for a deadline that has moved. A
 * monotonic reading only ever goes forward, at one second per second, which is the single property all
 * of this depends on.
 *
 * <p>Readings are meaningful only as differences, and only within one process. Comparing one to another
 * process's is meaningless, which is why nothing crossing the wire carries one: what the store reports
 * about a lease is a <b>remaining duration</b>, converted against the local reading on arrival.
 *
 * <p>Scheduling belongs here for the same reason. "In thirty seconds" is elapsed time, and a scheduler
 * that honoured it against a wall clock would sleep through a clock change.
 *
 * <p>The wall clock has one job left, and it is not timing: {@link #wallTime()} stamps the records, so
 * that a person reading a key knows roughly when something happened. Nothing compares those stamps, and
 * nothing decides anything by them. It lives on this interface rather than beside it because a caller
 * given two time sources can hand in two that disagree, and because which of the two a piece of code
 * should be using is a question worth answering once, here, instead of at every call site.
 *
 * <p>Tests pass a ticker they advance by hand, which is why a lease expiry or a four-hour handover takes
 * no wall-clock time at all. Constructed explicitly and handed in, like everything else here.
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
