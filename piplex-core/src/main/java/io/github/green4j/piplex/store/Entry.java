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
 * <p>Plus whether the store could {@link #confirmed() confirm} it. A plain read either lands or fails,
 * so everything a {@link CoordinationStore#get} returns is confirmed. A wait is where the third answer
 * comes from: a store whose polls were failing when the wait ran out reports the newest state it
 * managed to see, which is the right answer to "tell me when to look again" and no answer at all to
 * "how long is it since I last learnt anything about this key".
 *
 * @param value     the value held, or {@code null} when the key is absent or tombstoned
 * @param version   the version in force, opaque to piplex and never {@code null}
 * @param exists    whether the key currently holds a value
 * @param confirmed whether the store observed this at the moment it answered
 */
public record Entry(String value, String version, boolean exists, boolean confirmed) {

    /**
     * An entry for a key which holds no value.
     *
     * @param version the version in force
     * @return the entry
     */
    public static Entry absent(final String version) {
        return new Entry(null, version, false, true);
    }

    /**
     * An entry for a key which holds a value.
     *
     * @param value   the value
     * @param version the version it was written at
     * @return the entry
     */
    public static Entry of(final String value, final String version) {
        return new Entry(value, version, true, true);
    }

    /**
     * The same entry, as the newest thing a store could see rather than as what it observed at the end
     * of a wait.
     *
     * @param entry what was seen
     * @return it, marked unconfirmed
     */
    public static Entry unconfirmed(final Entry entry) {
        return new Entry(entry.value(), entry.version(), entry.exists(), false);
    }
}
