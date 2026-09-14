/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.exclusive;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * This run may proceed, for as long as this handle says so.
 *
 * <p>Admission is <b>held, not granted once</b>. The designation is watched for as long as the run
 * lasts, and the lease is renewed underneath it; if either stops being true the handle is revoked and
 * whatever registered with {@link #onRevoked} is told. That is what lets an operator name a new owner
 * while a run is in flight and have the old one actually stop.
 *
 * <p><b>What this does not promise.</b> It promises that at most one holder <i>believes</i> it is the
 * owner. It cannot promise that at most one is still <i>affecting</i> anything: between a lease lapsing
 * and its former holder noticing, both believe it. {@link #fencingToken()} is what closes that gap, and
 * only where the protected resource itself refuses a token lower than the highest it has seen. Where it
 * does not -- which is most places -- call {@link #isHeld()} before each irreversible step and accept
 * that stopping a run half way is not the same as making half-finished work harmless.
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
     * @param listener told once, with why
     */
    void onRevoked(Consumer<Revocation> listener);

    /**
     * Stops renewing and gives the lease up. Idempotent, and safe to call on a revoked handle.
     *
     * <p>Deliberately not {@code AutoCloseable}: giving a lease up is a round trip which can fail, and
     * a {@code void close()} in a try-with-resources would swallow that failure at exactly the moment
     * it matters most.
     *
     * @return completion
     */
    CompletionStage<Void> release();
}
