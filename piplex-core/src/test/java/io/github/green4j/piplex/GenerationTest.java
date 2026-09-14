/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationTest {

    @Test
    void refusesAGenerationWithNothingInIt() {
        assertThrows(IllegalArgumentException.class, () -> Generation.of(""));
        assertThrows(NullPointerException.class, () -> Generation.of(null));
    }

    @Test
    void comparesLexicographicallyAndSoSortsTenBeforeNine() {
        // Not a defect and not to be "fixed": the ordering is documented as lexicographic, and a caller
        // using a bare counter is told to zero-pad it. This test is here so that a later change to a
        // natural ordering has to be a deliberate one which breaks it.
        assertTrue(Generation.of("10").compareTo(Generation.of("9")) < 0);
        assertTrue(Generation.of("09").compareTo(Generation.of("10")) < 0);
    }

    @Test
    void ordersDatesTheWayTimeDoes() {
        final Generation first = Generation.ofDate(LocalDate.parse("2026-09-12"));
        final Generation next = Generation.ofDate(LocalDate.parse("2026-09-13"));
        final Generation overAMonthEnd = Generation.ofDate(LocalDate.parse("2026-10-01"));
        assertTrue(next.atLeast(first));
        assertFalse(first.atLeast(next));
        assertTrue(overAMonthEnd.atLeast(next), "ISO-8601 is why the month end needs no special case");
    }

    @Test
    void hasReachedItself() {
        final Generation one = Generation.of("2026-09-12");
        assertTrue(one.atLeast(Generation.of("2026-09-12")));
    }

    @Test
    void readsBackAsWhatWasPutIn() {
        assertEquals("2026-09-12", Generation.of("2026-09-12").toString());
        assertEquals("2026-09-12", Generation.ofDate(LocalDate.parse("2026-09-12")).value());
    }
}
