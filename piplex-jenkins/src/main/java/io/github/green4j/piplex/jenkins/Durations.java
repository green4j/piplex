/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import java.time.Duration;

/**
 * Turns what somebody types in a pipeline into a duration.
 *
 * <p>{@code 90m} and {@code 4h} are what a person writes; {@code PT90M} is what {@link Duration} parses.
 * Both are accepted, because refusing the first would be pedantry and refusing the second would surprise
 * anybody who already knows Java.
 */
final class Durations {

    private Durations() {
    }

    /**
     * @param text     what was typed, or {@code null} or blank for the fallback
     * @param fallback what a missing value means
     * @param what     the parameter's name, for the message when it is wrong
     * @return the duration
     */
    static Duration parse(final String text, final Duration fallback, final String what) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        final String trimmed = text.trim();
        final Duration parsed = suffixed(trimmed);
        if (parsed != null) {
            return positive(parsed, trimmed, what);
        }
        try {
            return positive(Duration.parse(trimmed), trimmed, what);
        } catch (final RuntimeException notADuration) {
            throw new IllegalArgumentException(
                    what + ": expected something like '90m', '4h' or 'PT1H30M', got '" + trimmed + "'",
                    notADuration);
        }
    }

    private static Duration suffixed(final String text) {
        final char unit = text.charAt(text.length() - 1);
        final String number = text.substring(0, text.length() - 1);
        if (number.isEmpty() || !number.chars().allMatch(Character::isDigit)) {
            return null;
        }
        final long amount = Long.parseLong(number);
        switch (unit) {
            case 's': return Duration.ofSeconds(amount);
            case 'm': return Duration.ofMinutes(amount);
            case 'h': return Duration.ofHours(amount);
            case 'd': return Duration.ofDays(amount);
            default: return null;
        }
    }

    private static Duration positive(final Duration value, final String text, final String what) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(what + ": must be positive, got '" + text + "'");
        }
        return value;
    }
}
