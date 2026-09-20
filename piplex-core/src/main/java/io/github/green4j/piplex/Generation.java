/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Which round of work a run belongs to -- a trading day, a batch, a build number.
 *
 * <p>The value is opaque to piplex and is <b>compared lexicographically</b>. That is enough for the
 * shapes worth using and it avoids inventing an ordering nobody asked for, but it does put the burden
 * on the caller: {@code "10"} sorts before {@code "9"}, so a bare counter must be zero-padded.
 * {@link #ofDate} is safe by construction, because ISO-8601 orders the same way as time does.
 *
 * @param value the generation, never blank
 */
public record Generation(String value) implements Comparable<Generation> {

    /**
     * @param value the generation
     */
    public Generation {
        Objects.requireNonNull(value, "value");
        // Blank, not just empty: generations are compared as text, so one made of spaces sorts below
        // every real value and is published as a milestone that releases nobody.
        if (value.isBlank()) {
            throw new IllegalArgumentException("generation must not be blank");
        }
    }

    /**
     * A generation naming a calendar day, as ISO-8601.
     *
     * @param date the day
     * @return the generation
     */
    public static Generation ofDate(final LocalDate date) {
        return new Generation(date.toString());
    }

    /**
     * A generation from an already-formatted value.
     *
     * @param value the generation
     * @return the generation
     */
    public static Generation of(final String value) {
        return new Generation(value);
    }

    /**
     * Whether this generation has reached the one given.
     *
     * @param other the generation asked for
     * @return {@code true} when this one is the same or later
     */
    public boolean atLeast(final Generation other) {
        return compareTo(other) >= 0;
    }

    @Override
    public int compareTo(final Generation other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
