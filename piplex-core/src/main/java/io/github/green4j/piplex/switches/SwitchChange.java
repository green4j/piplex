/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.switches;

/**
 * What a switch was, and what it is now.
 *
 * @param previous what was in force before, {@link Switch#ENABLED} when the key held nothing, or
 *                 {@code null} when it held a value that did not parse
 * @param inForce  what is in force now
 * @param changed  whether this call is what changed it
 */
public record SwitchChange(Switch previous, Switch inForce, boolean changed) {
}
