/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.milestone;

/**
 * What waiting for a milestone did.
 *
 * @param outcome what happened
 * @param inForce the record in force when the wait ended, {@code null} when the key held nothing
 */
public record AwaitResult(Outcome outcome, Milestone inForce) {

    /**
     * The ways a wait can end.
     */
    public enum Outcome {

        /** The milestone reached the generation asked for. */
        REACHED,

        /** It did not, within the time allowed. */
        TIMED_OUT
    }
}
