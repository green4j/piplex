/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Both clocks, moved by hand, so that a lapsing lease or a four-hour park costs a test nothing. Every
 * test in this module runs in real milliseconds because of this class -- there is not one sleep.
 *
 * <p>The two readings are separate on purpose. {@link #advance(Duration)} moves them together, as time
 * passing does. {@link #jump(Duration)} moves the wall clock alone, which is what an NTP correction or
 * an operator setting the date does -- and nothing piplex decides is allowed to notice.
 */
public final class ManualTime implements TimeSource {

    // Deliberately not zero: a reading of the monotonic clock has no origin anybody may assume, and a
    // test which accidentally treated it as "nanoseconds since the epoch" is caught by starting here.
    private static final long ORIGIN_NANOS = 1_000_000_000_000L;

    private final List<Scheduled> scheduled = new ArrayList<>();
    private final Instant started;
    private long nanos;
    private Duration wallOffset = Duration.ZERO;

    public ManualTime() {
        this(Instant.parse("2026-09-12T00:00:00Z"));
    }

    public ManualTime(final Instant start) {
        this.started = start;
        this.nanos = ORIGIN_NANOS;
    }

    /**
     * Moves time forward, running whatever came due on the way.
     *
     * @param by how far
     */
    public void advance(final Duration by) {
        final long target = nanos + by.toNanos();
        // Stepwise, stopping at each deadline on the way, because a real clock does not jump. Jumping
        // straight to the target and only then firing would let a lease lapse before the renewal which
        // was scheduled to keep it alive ever ran -- a failure of the harness, not of the code.
        while (true) {
            final Scheduled next = earliestBy(target);
            if (next == null) {
                break;
            }
            scheduled.remove(next);
            nanos = next.at;
            next.action.run();
        }
        nanos = target;
    }

    /**
     * Steps the wall clock without any time passing, the way an NTP correction does.
     *
     * @param by how far, backwards with a negative duration
     */
    public void jump(final Duration by) {
        wallOffset = wallOffset.plus(by);
    }

    /**
     * @return how many actions are still waiting to run
     */
    public int pending() {
        return scheduled.size();
    }

    @Override
    public long nanos() {
        return nanos;
    }

    @Override
    public Cancellable schedule(final Duration delay, final Runnable action) {
        final Scheduled item = new Scheduled(nanos + delay.toNanos(), action);
        scheduled.add(item);
        return () -> scheduled.remove(item);
    }

    @Override
    public Instant wallTime() {
        return started.plusNanos(nanos - ORIGIN_NANOS).plus(wallOffset);
    }

    private Scheduled earliestBy(final long target) {
        Scheduled earliest = null;
        for (final Scheduled candidate : new ArrayList<>(scheduled)) {
            if (candidate.at - target > 0L) {
                continue;
            }
            if (earliest == null || candidate.at - earliest.at < 0L) {
                earliest = candidate;
            }
        }
        return earliest;
    }

    private static final class Scheduled {
        private final long at;
        private final Runnable action;

        private Scheduled(final long at, final Runnable action) {
            this.at = at;
            this.action = action;
        }
    }
}
