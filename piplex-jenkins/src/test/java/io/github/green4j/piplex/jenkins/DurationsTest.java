/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurationsTest {

    @ParameterizedTest
    @CsvSource({
        "30s,     PT30S",
        "90m,     PT1H30M",
        "4h,      PT4H",
        "7d,      PT168H",
        "PT1H30M, PT1H30M",
        "' 4h ',  PT4H",
    })
    void readsWhatTheDocumentationSaysToType(final String typed, final String expected) {
        assertEquals(Duration.parse(expected), Durations.parse(typed, null, "lease"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void takesAnUnsetFieldToMeanTheFallback(final String unset) {
        assertEquals(Duration.ofMinutes(5), Durations.parse(unset, Duration.ofMinutes(5), "lease"));
    }

    // The documented default, written out. The core takes a zero handoverWait and only this layer
    // refused it, so a field filled in with what leaving it blank means failed the build.
    @Test
    void acceptsAZeroWhereZeroIsTheDocumentedDefault() {
        assertEquals(Duration.ZERO, Durations.orZero("0s", "handoverWait"));
        assertEquals(Duration.ZERO, Durations.orZero(null, "handoverWait"));
        assertEquals(Duration.ofHours(4), Durations.orZero("4h", "handoverWait"));
        assertThrows(IllegalArgumentException.class, () -> Durations.orZero("PT-1S", "handoverWait"));
    }

    @Test
    void refusesAZeroEverywhereElse() {
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("0s", null, "lease"));
    }

    // A number no duration can hold used to arrive as a NumberFormatException or an ArithmeticException
    // from inside the parser, naming neither the parameter nor what was typed.
    @ParameterizedTest
    @ValueSource(strings = {"soon", "4hours", "1e3s", "99999999999999999999d", "999999999999999999d"})
    void namesTheParameterAndTheTextForAnythingItCannotRead(final String typed) {
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Durations.parse(typed, null, "renewEvery"));
        assertTrue(refused.getMessage().startsWith("renewEvery: "), refused.getMessage());
        assertTrue(refused.getMessage().contains(typed.trim()), refused.getMessage());
    }
}
