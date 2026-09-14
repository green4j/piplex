/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.example;

import io.github.green4j.piplex.Generation;
import io.github.green4j.piplex.Piplex;
import io.github.green4j.piplex.milestone.AwaitResult;
import io.github.green4j.piplex.milestone.PublishResult;

import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.CompletionStage;

/**
 * A pipeline on one controller starts because a pipeline on another finished.
 *
 * <p>Today the second one starts by the clock -- "new day runs at 05:30, end-of-day should be done by
 * then" -- and when it is not, the day is imported half-empty and nothing says so. Here the waiter
 * names the day it needs and waits for that day to be published, however long it takes.
 *
 * <p>The two properties below are what make it safe to restart either side.
 *
 * <p>Publishing is <b>monotonic</b>: a milestone never moves backwards, so re-running yesterday's
 * producer today does not tell every waiter that yesterday is the newest data.
 *
 * <p>Waiting compares <b>state</b>, not events. It reads, then waits for a change, then reads again. It
 * has to: the store coalesces changes, so a waiter counting events would miss the one it needed. The
 * happy side effect is that a waiter has no position of its own, and a restarted build is simply
 * correct.
 */
public final class MilestoneExample {

    private static final String MILESTONE = "data/euc1";

    private MilestoneExample() {
    }

    /**
     * @param args ignored
     * @throws InterruptedException if the pause before publishing is interrupted
     */
    public static void main(final String[] args) throws InterruptedException {
        try (Controllers controllers = new Controllers()) {
            final Piplex frankfurt = controllers.controller("euc1-blue");
            final Piplex milan = controllers.controller("eus1-blue");
            final Generation today = Examples.today();

            Examples.say("=== Milestone: new-day waits for end-of-day, not for 05:30 ===");

            // Milan's new-day build is up and waiting. It holds no executor while it does.
            final CompletionStage<AwaitResult> waiting =
                    milan.milestones().awaitAtLeast(MILESTONE, today, Duration.ofSeconds(20));
            Examples.say("Milan is waiting for " + MILESTONE + " to reach " + today);

            Thread.sleep(700L); // Frankfurt's EOD is still running; in production this is hours

            final PublishResult published = Examples.await(
                    frankfurt.milestones().publish(MILESTONE, today, "euc1-blue", "eod#142"));
            Examples.say("Frankfurt finished EOD and published: " + published.outcome());

            final AwaitResult reached = Examples.await(waiting);
            Examples.say("Milan woke up: " + reached.outcome()
                    + ", published by " + reached.inForce().by() + " in run " + reached.inForce().runId());

            monotonic(frankfurt, today);
            notYet(milan, today);
        }
    }

    private static void monotonic(final Piplex frankfurt, final Generation today) {
        // A retried producer costs one read and changes nothing.
        Examples.say("Frankfurt re-runs today's EOD    -> "
                + Examples.await(frankfurt.milestones()
                        .publish(MILESTONE, today, "euc1-blue", "eod#143")).outcome());

        // And a backfill of an older day cannot pull the milestone back under the waiters.
        final Generation yesterday = Generation.ofDate(LocalDate.now().minusDays(1L));
        Examples.say("Frankfurt backfills " + yesterday + "     -> "
                + Examples.await(frankfurt.milestones()
                        .publish(MILESTONE, yesterday, "euc1-blue", "backfill#3")).outcome());
    }

    private static void notYet(final Piplex milan, final Generation today) {
        // Tomorrow has not happened. A waiter that gives up says what it found instead, so the build
        // log shows "waited for tomorrow, the newest is today" rather than an unexplained timeout.
        final Generation tomorrow = Generation.ofDate(LocalDate.now().plusDays(1L));
        final AwaitResult gaveUp = Examples.await(
                milan.milestones().awaitAtLeast(MILESTONE, tomorrow, Duration.ofSeconds(1L)));
        Examples.say("Milan waits for " + tomorrow + "        -> " + gaveUp.outcome()
                + ", newest is " + gaveUp.inForce().generation());
    }
}
