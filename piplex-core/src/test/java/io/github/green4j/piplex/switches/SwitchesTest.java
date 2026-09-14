/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.switches;

import io.github.green4j.piplex.ContendedException;
import io.github.green4j.piplex.LosingCasStore;
import io.github.green4j.piplex.ManualTime;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.memory.InMemoryCoordinationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwitchesTest {

    private static final String KEY = "eod";

    private CoordinationStore store;
    private Switches switches;

    @BeforeEach
    void setUp() {
        store = new InMemoryCoordinationStore(new ManualTime());
        switches = new Switches(store);
    }

    @Test
    void isOnWhenNobodyHasEverWrittenTheKey() {
        // The guarantee worth having: a store which has never been written to, or one whose key somebody
        // deleted, must not silently stop the work everywhere.
        final Switch inForce = join(switches.current(KEY));
        assertTrue(inForce.enabled());
        assertNull(inForce.reason());
        assertEquals(Switch.ENABLED, inForce);
    }

    @Test
    void stopsTheWorkAndSaysWhy() {
        final Switch off = join(switches.disable(KEY, "INC-4821"));
        assertFalse(off.enabled());
        assertEquals("INC-4821", off.reason());
        assertEquals(off, join(switches.current(KEY)));
    }

    @Test
    void letsTheWorkRunAgain() {
        join(switches.disable(KEY, "INC-4821"));
        assertEquals(Switch.ENABLED, join(switches.enable(KEY)));
        assertTrue(join(switches.current(KEY)).enabled());
    }

    @Test
    void writesNothingWhenWhatIsAskedForIsAlreadyInForce() {
        join(switches.disable(KEY, "INC-4821"));
        final String versionAfterFirst = version();

        assertEquals(new Switch(false, "INC-4821"), join(switches.disable(KEY, "INC-4821")));
        assertEquals(versionAfterFirst, version(),
                "the same switch asked for twice must not rewrite the key");
    }

    @Test
    void treatsADifferentReasonAsAChange() {
        join(switches.disable(KEY, "INC-4821"));
        assertEquals("INC-4830", join(switches.disable(KEY, "INC-4830")).reason());
    }

    @Test
    void enablingWhatIsAlreadyOnWritesNothing() {
        assertEquals(Switch.ENABLED, join(switches.enable(KEY)));
        assertFalse(join(store.get(Switches.keyOf(KEY))).exists(),
                "an absent key already means on, so there is nothing to write");
    }

    @Test
    void keepsSwitchesUnderAPrefixAnOperatorCanType() {
        assertEquals("piplex/enabled/eod", Switches.keyOf(KEY));
        assertEquals("piplex/enabled/eod/euc1-blue", Switches.keyOf("eod/euc1-blue"));
    }

    @Test
    void refusesARecordWhichDoesNotSayWhetherItIsOnOrOff() {
        // The one field that must be there. Read as "off" by mistake it would stop the work everywhere,
        // and read as "on" it would let run what an operator had switched off, so neither default is
        // available and it has to be refused.
        assertThrows(RuntimeException.class, () -> Switch.parse("{\"reason\":\"INC-4821\"}"));
        assertThrows(RuntimeException.class, () -> Switch.parse("{\"enabled\":\"true\"}"));
    }

    @Test
    void givesUpAfterABoundedNumberOfLostWrites() {
        final LosingCasStore losing = new LosingCasStore(store);
        final Switches contended = new Switches(losing);

        final CompletionException thrown = assertThrows(
                CompletionException.class, () -> join(contended.disable(KEY, "INC-4821")));
        final ContendedException cause = assertInstanceOf(ContendedException.class, thrown.getCause());
        assertEquals(Switches.keyOf(KEY), cause.key());
        assertEquals(8, losing.attempts(), "bounded, and the bound is the one the class states");
    }

    private String version() {
        return join(store.get(Switches.keyOf(KEY))).version();
    }

    @Test
    void refusesWhatIsNotARecordAtAll() {
        // The shapes a key ends up holding once somebody edits it by hand. The text goes in the
        // message because it is the only copy of what the key held.
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Switch.parse("{\"enabled\""))
                .getMessage().contains("{\"enabled\""));
        assertThrows(IllegalArgumentException.class, () -> Switch.parse("[1,2]"));
        assertThrows(IllegalArgumentException.class, () -> Switch.parse(""));
    }

    private static <T> T join(final CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
