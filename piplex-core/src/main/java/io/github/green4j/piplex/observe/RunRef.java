/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.observe;

import io.github.green4j.piplex.Environment;
import io.github.green4j.piplex.Generation;

/**
 * Which run an event is about.
 *
 * <p>{@code environment}, {@code key} and {@code generation} together are the correlation key, and
 * there is deliberately no synthetic run id on top of them. They already name the logical unit of
 * work, so every controller's lines for one day's attempt at one key -- the one that worked and the
 * three that waited -- gather under them with nothing further to invent. The environment is part of
 * it because one controller may run the same key in several of them, and then the key alone names two
 * different pieces of work.
 *
 * @param environment which set of orchestrations the work belongs to
 * @param key         what is being competed for
 * @param generation  which round of work, or {@code null} when the work has no rounds
 * @param ownerId     which controller
 * @param runId       which run on it
 */
public record RunRef(Environment environment,
                     String key,
                     Generation generation,
                     String ownerId,
                     String runId) {
}
