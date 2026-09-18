/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.switches;

import io.github.green4j.piplex.Environment;
import io.github.green4j.piplex.ManualTime;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.memory.InMemoryCoordinationStore;
import io.github.green4j.piplex.observe.TextPiplexObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwitchesTest {

    private static final String KEY = "eod";

    private final ManualTime time = new ManualTime();
    private CoordinationStore store;
    private Switches switches;

    @BeforeEach
    void setUp() {
        store = new InMemoryCoordinationStore(time);
        switches = new Switches(store, time);
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
    void stopsTheWorkSayingWhyAndLetsItRunAgain() {
        final SwitchChange change = join(switches.disable(KEY, "INC-4821"));
        final Switch off = change.inForce();
        assertEquals(new SwitchChange(Switch.ENABLED, new Switch(false, "INC-4821", time.wallTime()), true), change);
        assertEquals(off, join(switches.current(KEY)));

        time.advance(Duration.ofMinutes(1L));
        final SwitchChange on = join(switches.enable(KEY));
        assertEquals(new SwitchChange(off, new Switch(true, null, time.wallTime()), true), on);
        assertEquals(on.inForce(), join(switches.current(KEY)));
    }

    @Test
    void writesNothingWhenWhatIsAskedForIsAlreadyInForce() {
        assertFalse(join(switches.enable(KEY)).changed());
        assertFalse(join(store.get(Switches.keyOf(Environment.DEFAULT, KEY))).exists(),
                "An absent key already means on, so there is nothing to write");

        final Switch off = join(switches.disable(KEY, "INC-4821")).inForce();
        final String versionAfterFirst = version();

        // As a designation keeps its: only what piplex acts on is compared, so asking for "off" again
        // changes nothing, whatever reason it gives.
        time.advance(Duration.ofMinutes(1L));
        assertEquals(new SwitchChange(off, off, false), join(switches.disable(KEY, "INC-4830")));
        assertEquals(versionAfterFirst, version(), "The same switch asked for twice must not rewrite the key");
    }

    @Test
    void keepsAnOwnersSwitchApartFromTheWorkSwitchesUnderTheSameKey() {
        assertEquals("piplex/default/enabled/eod/euc1-blue", Switches.keyOf(Environment.DEFAULT, "eod/euc1-blue"));
        assertEquals("piplex/default/enabled/eod/@blue",
                Switches.keyOf(Environment.DEFAULT, Switches.ownerKey(KEY, "blue")));
        assertTrue(Switches.namesAnOwner(Switches.ownerKey(KEY, "team/blue")));
        assertFalse(Switches.namesAnOwner("eod/blue"));
    }

    @Test
    void readsWhatAPersonTypedAsLongAsItSaysWhetherItIsOnOrOff() {
        assertEquals(new Switch(false, null, null), Switch.parse("{\"enabled\":false}"));
        assertEquals(new Switch(false, "INC-4821", null),
                Switch.parse("{\"enabled\":false,\"reason\":\"INC-4821\",\"at\":\"yesterday\"}"));
        // The one field that must be there. Read as "off" by mistake it would stop the work everywhere,
        // and read as "on" it would let run what an operator had switched off, so neither default is
        // available and it has to be refused.
        assertThrows(RuntimeException.class, () -> Switch.parse("{\"reason\":\"INC-4821\"}"));
        assertThrows(RuntimeException.class, () -> Switch.parse("{\"enabled\":\"true\"}"));
    }

    private String version() {
        return join(store.get(Switches.keyOf(Environment.DEFAULT, KEY))).version();
    }

    @Test
    void repairsAValueThatWillNotParse() {
        join(store.compareAndSet(Switches.keyOf(Environment.DEFAULT, KEY),
                CoordinationStore.INITIAL_VERSION, "{\"owner\""));

        final Switch off = new Switch(false, "INC-4821", time.wallTime());
        assertEquals(new SwitchChange(null, off, true), join(switches.repair(KEY, false, "INC-4821")));
        assertEquals(off, join(switches.current(KEY)));
        assertFalse(join(switches.repair(KEY, false, "INC-4830")).changed(), "Readable, it is a set");
    }

    @Test
    void saysSoOnlyWhenItChangedSomething() {
        final List<String> lines = new ArrayList<>();
        final Switches logged = new Switches(store, time, new TextPiplexObserver(lines::add));

        join(logged.enable(KEY));
        join(logged.disable(KEY, "INC-4821"));
        join(logged.disable(KEY, "INC-4830"));
        join(logged.enable(KEY));

        assertEquals(List.of(
                "SWITCHED environment=default key=eod enabled=false reason=INC-4821",
                "SWITCHED environment=default key=eod enabled=true"), lines);
    }

    private static <T> T join(final CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
