/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.example;

import io.github.green4j.piplex.Piplex;
import io.github.green4j.piplex.exclusive.Admission;
import io.github.green4j.piplex.exclusive.Admitted;
import io.github.green4j.piplex.exclusive.ExclusiveRequest;
import io.github.green4j.piplex.exclusive.Revocation;
import io.github.green4j.piplex.switches.Switch;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stopping a job everywhere, during an incident, without a deploy.
 *
 * <p>The thing this replaces is a piece of folklore: commenting the schedule out of one deployment's
 * configuration and running the job builder against that server by hand. It is slow when slowness is
 * expensive, it is per-deployment when the incident is not, and it does nothing at all about the build
 * already running.
 *
 * <p>One write does all three. Runs that have not started are refused with the reason in their log, and
 * -- because admission is held rather than granted -- the run already in flight is revoked by the same
 * machinery that handles a change of primary. An absent key means on, so a store nobody has written to
 * never silently stops the work.
 */
public final class DrainSwitchExample {

    private static final String KEY = "new-day";
    private static final String SWITCH = "new-day-switch";

    private DrainSwitchExample() {
    }

    /**
     * @param args ignored
     * @throws InterruptedException if the wait for the revocation is interrupted
     */
    public static void main(final String[] args) throws InterruptedException {
        try (Controllers controllers = new Controllers()) {
            final Piplex operator = controllers.operator();
            final Piplex milan = controllers.controller("eus1-blue");

            Examples.say("=== Switch: turn the work off everywhere, including what is running ===");

            final Admitted running = Examples.admitted(
                    Examples.await(milan.runs().begin(request("new-day#901"))));
            Examples.say("Milan is running new-day, fencing token " + running.fencingToken());

            final CountDownLatch stopped = new CountDownLatch(1);
            final AtomicReference<Revocation> why = new AtomicReference<>();
            running.onRevoked(revocation -> {
                why.set(revocation);
                stopped.countDown();
            });

            final Switch off = Examples.await(operator.switches().disable(SWITCH, "INC-4711, bad reference data"))
                    .inForce();
            Examples.say("Operator switched " + SWITCH + " off: " + off.reason());

            if (!stopped.await(10L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The run was never stopped");
            }
            Examples.say("Milan's run was stopped: " + why.get().reason()
                    + "; still holds it: " + running.isHeld());
            // The work has stopped, so the lease can go back.
            Examples.await(running.release());

            final Admission refused = Examples.await(milan.runs().begin(request("new-day#902")));
            Examples.say("The next build does not even start -> " + describe(refused));

            Examples.await(operator.switches().enable(SWITCH));
            Examples.say("Operator switched " + SWITCH + " back on");

            final Admission after = Examples.await(milan.runs().begin(request("new-day#903")));
            Examples.say("And the one after that              -> " + describe(after));
            if (after instanceof Admitted admitted) {
                Examples.await(admitted.release());
            }
        }
    }

    private static ExclusiveRequest request(final String runId) {
        return ExclusiveRequest.builder(KEY)
                .ownedBy("eus1-blue")
                .runId(runId)
                .enabledBy(SWITCH)          // read the switch from piplex/enabled/new-day-switch
                .lease(Duration.ofSeconds(4))
                .build();
    }

    private static String describe(final Admission admission) {
        if (admission instanceof Admitted admitted) {
            return "ADMITTED, fencing token " + admitted.fencingToken();
        }
        if (admission instanceof Admission.Disabled disabled) {
            return "switched off: " + disabled.reason();
        }
        return admission.toString();
    }
}
