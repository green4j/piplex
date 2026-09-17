/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationTest {

    @Test
    void refusesAGenerationWithNothingInIt() {
        assertThrows(IllegalArgumentException.class, () -> Generation.of(""));
        assertThrows(NullPointerException.class, () -> Generation.of(null));
    }

    // "10" before "9" is not a defect: the ordering is documented as lexicographic, and a caller using a
    // bare counter is told to zero-pad it. A change to a natural ordering has to break this on purpose.
    // ISO-8601 dates are why the month end needs no special case.
    @ParameterizedTest
    @CsvSource({
        "10,         9,          false",
        "10,         09,         true",
        "2026-09-12, 2026-09-12, true",
        "2026-09-13, 2026-09-12, true",
        "2026-09-12, 2026-09-13, false",
        "2026-10-01, 2026-09-13, true",
    })
    void comparesLexicographically(final String one, final String other, final boolean atLeast) {
        assertEquals(atLeast, Generation.of(one).atLeast(Generation.of(other)));
    }

    @Test
    void readsBackAsWhatWasPutIn() {
        assertEquals("2026-09-12", Generation.of("2026-09-12").toString());
        assertEquals("2026-09-12", Generation.ofDate(LocalDate.parse("2026-09-12")).value());
    }
}
