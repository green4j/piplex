/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.example;

import io.github.green4j.piplex.Generation;
import io.github.green4j.piplex.Piplex;
import io.github.green4j.piplex.exclusive.Admission;
import io.github.green4j.piplex.exclusive.Admitted;
import io.github.green4j.piplex.exclusive.DesignationChange;
import io.github.green4j.piplex.exclusive.ExclusiveRequest;
import io.github.green4j.piplex.exclusive.Revocation;
import io.github.green4j.piplex.milestone.PublishResult;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The end-of-day job must run in the primary region, and the primary region changed while it was
 * running.
 *
 * <p>This is the hard half, and the reason the designated policy exists at all. Which region is primary
 * is not a race anybody should win -- it is a fact an operator decides -- so a key names the owner and
 * the other controllers read it. The lease is still taken by the designated owner, because it answers a
 * different question: two runs of the same job on the same controller.
 *
 * <p>Two things here are worth reading closely.
 *
 * <p>The controller which is not designated <b>parks</b> instead of exiting. If it exited there would
 * be nobody left to take over, and on a nightly schedule the next chance would be tomorrow. Parked, it
 * is already waiting when the designation moves.
 *
 * <p>And admission is <b>held</b>, not granted. The holder watches the same key it was admitted by, so
 * changing the designation under a running job revokes it -- which is what a host turns into an
 * interrupted build.
 *
 * <p>Neither controller learns anything from the other. Everything below travels through the store.
 */
public final class DesignatedHandoverExample {

    private static final String KEY = "eod";
    private static final String DESIGNATION = "eod-owner";
    private static final String MILESTONE = "data/euc1";

    private DesignatedHandoverExample() {
    }

    /**
     * @param args ignored
     * @throws InterruptedException if the wait for the handover is interrupted
     */
    public static void main(final String[] args) throws InterruptedException {
        try (Controllers controllers = new Controllers()) {
            final Piplex operator = controllers.operator();
            final Piplex frankfurt = controllers.controller("euc1-blue");
            final Piplex milan = controllers.controller("eus1-blue");
            final Generation today = Examples.today();

            Examples.say("=== Designated: the operator says which region is primary ===");
            final DesignationChange initial = Examples.await(operator.designations()
                    .designate(DESIGNATION, "euc1-blue", "primary region"));
            Examples.say("Designated " + initial.inForce().owner() + ", change #" + initial.inForce().seq());

            // Frankfurt is designated, so it runs.
            final Admitted holder = (Admitted) Examples.await(
                    frankfurt.runs().begin(request("euc1-blue", "eod#142", today)));
            Examples.say("Frankfurt is running EOD, fencing token " + holder.fencingToken());

            final CountDownLatch revoked = new CountDownLatch(1);
            final AtomicReference<Revocation> why = new AtomicReference<>();
            holder.onRevoked(revocation -> {
                why.set(revocation);
                revoked.countDown();
            });

            // Milan is not designated. Its build does not fail and does not end -- it waits, because in
            // four hours it may be the one that has to run. This stage stays unfinished for now.
            final CompletionStage<Admission> milanAsked =
                    milan.runs().begin(request("eus1-blue", "eod#77", today));
            Thread.sleep(500L); // let it settle into the park; in production the change comes hours later

            Examples.say("--- Frankfurt goes down for maintenance; the operator moves the primary ---");
            final DesignationChange moved = Examples.await(operator.designations()
                    .designate(DESIGNATION, "eus1-blue", "INC-4711, euc1 maintenance"));
            Examples.say("Primary moved from " + moved.previous().owner()
                    + " to " + moved.inForce().owner() + ", change #" + moved.inForce().seq());

            handover(revoked, why, holder, milanAsked, today);
        }
    }

    private static void handover(final CountDownLatch revoked,
                                 final AtomicReference<Revocation> why,
                                 final Admitted holder,
                                 final CompletionStage<Admission> milanAsked,
                                 final Generation today) throws InterruptedException {
        if (!revoked.await(10L, TimeUnit.SECONDS)) {
            throw new IllegalStateException("The holder was never revoked");
        }
        Examples.say("Frankfurt's run was stopped: " + why.get().reason()
                + ", now " + why.get().newOwner() + "; still holds it: " + holder.isHeld());
        // Frankfurt's work has stopped, so its lease can go back and Milan need not wait it out.
        Examples.await(holder.release());

        final Admitted taken = (Admitted) Examples.await(milanAsked);
        Examples.say("Milan stopped waiting and is running EOD, fencing token " + taken.fencingToken());

        // The milestone is published on success and only on success. Frankfurt's half-finished run
        // published nothing, so whatever waits on this milestone kept waiting rather than starting on
        // a partial day -- which is the composition the two primitives are for. Published while the
        // lease is still held, then given back: the order completedWhen rests on.
        final PublishResult published = Examples.await(taken.completeAndRelease());
        Examples.say("Milan published " + MILESTONE + " at " + today + ": " + published.outcome());
    }

    private static ExclusiveRequest request(final String ownerId,
                                            final String runId,
                                            final Generation generation) {
        return ExclusiveRequest.builder(KEY)
                .ownedBy(ownerId)
                .runId(runId)
                .designatedBy(DESIGNATION) // read who is primary from piplex/designated/eod-owner
                .generation(generation)     // which day's work this is
                .completedWhen(MILESTONE)   // if that day is already published, there is nothing to do
                .lease(Duration.ofSeconds(4))
                .handoverWait(Duration.ofMinutes(5))
                .build();
    }
}
