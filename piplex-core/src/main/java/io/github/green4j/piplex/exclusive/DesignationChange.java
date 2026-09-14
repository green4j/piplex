/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.exclusive;

/**
 * What a designation was, and what it is now.
 *
 * <p>Both halves are returned because the caller is the place where the change becomes a record. The
 * store keeps only the latest value, so "it used to be euc1-blue" exists exactly as long as it takes
 * whoever made the change to write it down.
 *
 * @param previous what was in force before, or {@code null} when nothing was
 * @param inForce  what is in force now
 * @param changed  whether this call is what changed it
 */
public record DesignationChange(Designation previous, Designation inForce, boolean changed) {
}
