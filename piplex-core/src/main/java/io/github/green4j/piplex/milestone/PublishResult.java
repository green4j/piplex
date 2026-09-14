/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.milestone;

/**
 * What publishing a milestone did.
 *
 * @param outcome what happened
 * @param inForce the record held afterwards
 */
public record PublishResult(Outcome outcome, Milestone inForce) {

    /**
     * The ways publishing can end. There is no failure case: a milestone which is already at or beyond
     * the generation offered is the normal result of re-running a producer, not a problem.
     */
    public enum Outcome {

        /** The milestone moved forward. */
        PUBLISHED,

        /** It was already there, so nothing was written. */
        ALREADY_AT_OR_AHEAD
    }
}
