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

import java.time.Duration;

/**
 * One job, four controllers, and it must run once.
 *
 * <p>This is the easy half of the problem: the controllers are interchangeable, so it does not matter
 * which one runs -- only that one does. No designation key is named, so whoever takes the lease works
 * and the rest are told somebody else is already at it.
 *
 * <p>Notice what the loser is told. Not "no" but "held by euc1-blue/compaction#1" -- the run, not the
 * deployment, because two runs on one controller are exactly as wrong as two runs on two, and the lease
 * has to refuse them both.
 *
 * <p>Run it: {@code ./gradlew :piplex-example:run -PmainClass=ElectedRunExample}, or from an IDE.
 */
public final class ElectedRunExample {

    private static final String KEY = "nightly-compaction";

    private ElectedRunExample() {
    }

    /**
     * @param args ignored
     */
    public static void main(final String[] args) {
        try (Controllers controllers = new Controllers()) {
            final Piplex frankfurt = controllers.controller("euc1-blue");
            final Piplex milan = controllers.controller("eus1-blue");

            Examples.say("=== Elected: nobody is designated, so whoever takes the lease runs ===");

            final Admission first = Examples.await(
                    frankfurt.runs().begin(request("euc1-blue", "compaction#1")));
            Examples.say("Frankfurt asked first  -> " + describe(first));

            final Admission second = Examples.await(
                    milan.runs().begin(request("eus1-blue", "compaction#7")));
            Examples.say("Milan asked meanwhile  -> " + describe(second));

            // The work would go here, under the one that was admitted. isHeld() is what a run checks
            // before anything it cannot take back: admission is held, not granted once, and between
            // two irreversible steps it can be taken away.
            final Admitted holder = (Admitted) first;
            Examples.say("Frankfurt is working, still holds it: " + holder.isHeld());

            Examples.await(holder.release());
            Examples.say("Frankfurt finished and released it");

            final Admission retry = Examples.await(
                    milan.runs().begin(request("eus1-blue", "compaction#8")));
            Examples.say("Milan's next run      -> " + describe(retry));
            if (retry instanceof Admitted admitted) {
                Examples.await(admitted.release());
            }
        }
    }

    private static ExclusiveRequest request(final String ownerId, final String runId) {
        return ExclusiveRequest.builder(KEY)
                .ownedBy(ownerId)
                .runId(runId)
                // Short, because the lease bounds how long a takeover waits after a controller dies --
                // it has nothing to do with how long the work itself runs. It is renewed while held.
                .lease(Duration.ofSeconds(5))
                .build();
    }

    private static String describe(final Admission admission) {
        if (admission instanceof Admitted admitted) {
            return "ADMITTED, fencing token " + admitted.fencingToken();
        }
        if (admission instanceof Admission.HeldByOther held) {
            return "held by " + held.heldBy();
        }
        return admission.toString();
    }
}
