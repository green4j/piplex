/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.exclusive;

import io.github.green4j.piplex.milestone.PublishResult;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Ownership held by one run until release or revocation.
 *
 * <p>The handle renews its lease and watches configured guards. Register {@link #onRevoked} before
 * starting work, check {@link #isHeld()} before irreversible steps, and pass {@link #fencingToken()} to
 * resources that can reject stale holders.
 */
public non-sealed interface Admitted extends Admission {

    /**
     * @return the identity this run was admitted under
     */
    String ownerId();

    /**
     * @return the run which was admitted
     */
    String runId();

    /**
     * @return the token which increases on every acquisition, to be passed to whatever it protects
     */
    long fencingToken();

    /**
     * Whether this run is still the owner, as far as is currently known.
     *
     * <p>Ask before each step which cannot be undone, not once at the start.
     *
     * @return {@code true} while ownership holds
     */
    boolean isHeld();

    /**
     * Registers something to be told when ownership is lost. A listener added after revocation is told
     * at once, so there is no race between starting the work and subscribing to losing it.
     *
     * <p>Revocation stops the renewals but does not give the lease up: the work may still be running.
     * Call {@link #release()} once it has actually stopped; left alone, the lease lapses within its
     * term.
     *
     * <p>Listeners are told before the observer, on the same threads and under the same rules as
     * {@link io.github.green4j.piplex.observe.PiplexObserver}: quickly, without blocking, and with
     * nothing locked, so {@link #release()} may be called from one. Stopping work that takes a while
     * is started here, not waited for.
     *
     * @param listener told once, with why
     */
    void onRevoked(Consumer<Revocation> listener);

    /**
     * Stops renewing and gives the lease up. Idempotent, and the way a revoked handle's lease goes
     * back once its work has stopped.
     *
     * <p>Deliberately not {@code AutoCloseable}: giving a lease up is a round trip which can fail, and
     * a {@code void close()} in a try-with-resources would swallow that failure at exactly the moment
     * it matters most.
     *
     * @return completion; a failed stage says the lease may still be standing, and the controller
     *         taking over waits it out instead of taking it at once
     */
    CompletionStage<Void> release();

    /**
     * Publishes the request's {@code completedWhen} milestone at its generation, then gives the lease up.
     *
     * <p>In that order, which is the one {@link ExclusiveRequest#completedWhen()} rests on: published
     * after the release, the milestone leaves a gap in which the next candidate is admitted to redo the
     * work. Call it on success only, like {@link io.github.green4j.piplex.milestone.Milestones#publish}.
     *
     * <p>The lease is given up whatever the publish did. Nothing is published once ownership is gone:
     * work finished by a run that was revoked is not that run's to announce. A publish already sent is
     * not called back, though, and a {@link #release()} asked for meanwhile -- by a revocation listener,
     * say -- waits for it to settle.
     *
     * @return what the publish did; failed when ownership is gone, when the publish failed, or -- with
     *         the milestone already published -- when the release failed. Failed with nothing done at
     *         all, the lease still held, when the request names no {@code completedWhen}
     */
    CompletionStage<PublishResult> completeAndRelease();

    /**
     * Stops renewing and watching without giving the lease up, and without telling any listener.
     * Idempotent; {@link #release()} afterwards does nothing.
     *
     * <p>For a host that is going away while its work is not: the lease lapses within its term, and a
     * host coming back inside that term retakes it under the same identity with the same fencing
     * token. Given up instead, it would come back with a new one.
     */
    void abandon();
}
