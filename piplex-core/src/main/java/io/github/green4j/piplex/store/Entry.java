/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.store;

/**
 * The state of one key: its value, the version that value was written at, and whether it is there at all.
 *
 * @param value   the value held, or {@code null} when the key is absent or tombstoned
 * @param version the version in force, opaque to piplex and never {@code null}
 * @param exists  whether the key currently holds a value
 */
public record Entry(String value, String version, boolean exists) {

    /**
     * An entry for a key which holds no value.
     *
     * @param version the version in force
     * @return the entry
     */
    public static Entry absent(final String version) {
        return new Entry(null, version, false);
    }

    /**
     * An entry for a key which holds a value.
     *
     * @param value   the value
     * @param version the version it was written at
     * @return the entry
     */
    public static Entry of(final String value, final String version) {
        return new Entry(value, version, true);
    }
}
