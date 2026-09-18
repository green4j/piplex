/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.exclusive;

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

class DesignationsTest {

    private static final String KEY = "eod";
    private static final String BLUE = "euc1-blue";
    private static final String GREEN = "euc1-green";

    private ManualTime time;
    private CoordinationStore store;
    private Designations designations;

    @BeforeEach
    void setUp() {
        time = new ManualTime();
        store = new InMemoryCoordinationStore(time);
        designations = new Designations(store, time);
    }

    @Test
    void hasNobodyDesignatedUntilSomebodyIsAndRefusesToDesignateNobody() {
        // Null and not a record standing for nobody: "not designated yet" and "designated to nobody"
        // would otherwise be the same value, and only the first of them is a state this has.
        assertThrows(NullPointerException.class, () -> designations.designate(KEY, null, "INC-4821"));
        assertNull(join(designations.current(KEY)), "And it never reached the store");
    }

    @Test
    void namesEachOwnerWithWhoHeldItBeforeAndCountsTheChanges() {
        time.advance(Duration.ofHours(3L));
        final DesignationChange first = join(designations.designate(KEY, BLUE, "INC-4821"));

        assertTrue(first.changed());
        assertNull(first.previous());
        assertEquals(new Designation(BLUE, "INC-4821", time.wallTime(), null, 1L), first.inForce(),
                "Stamped from the time source, not the system clock");

        final DesignationChange second = join(designations.designate(KEY, GREEN, "INC-4830"));

        assertTrue(second.changed());
        assertEquals(first.inForce(), second.previous());
        assertEquals(GREEN, second.inForce().owner());
        assertEquals(BLUE, second.inForce().prev());
        assertEquals(2L, second.inForce().seq());
    }

    @Test
    void designatingTheOwnerAlreadyInForceChangesNothing() {
        join(designations.designate(KEY, BLUE, "INC-4821"));
        final String versionAfterFirst = version();

        time.advance(Duration.ofMinutes(1L));
        final DesignationChange again = join(designations.designate(KEY, BLUE, "INC-4830"));

        assertFalse(again.changed());
        assertEquals(1L, again.inForce().seq(), "The counter says how many times it moved, and it did not");
        assertEquals("INC-4821", again.inForce().reason(), "The reason it is in force for is the first one");
        assertEquals(Duration.ofMinutes(1L), Duration.between(again.inForce().at(), time.wallTime()),
                "And so is the time");
        assertEquals(versionAfterFirst, version(), "And the key was not rewritten at all");
    }

    @Test
    void readsWhatAPersonTypedAsLongAsItNamesAnOwner() {
        // Keys here are read and edited by people, so a half-written record has to be refused rather
        // than read as a designation naming nobody.
        assertThrows(RuntimeException.class, () -> Designation.parse("{\"seq\":1}"));
        // Only the owner decides anything, so a stamp typed in wrong must not make the key unreadable.
        assertEquals(new Designation(BLUE, null, null, null, 0L),
                Designation.parse("{\"owner\":\"" + BLUE + "\",\"at\":\"nope\",\"seq\":\"x\",\"prev\":1}"));
        // seq is optional, so a record typed in by hand starts the counter at zero and the next change
        // makes it one -- the value a first designate() would have had.
        assertEquals(new Designation(BLUE, null, null, null, 0L), Designation.parse("{\"owner\":\"euc1-blue\"}"));
    }

    private String version() {
        return join(store.get(Designations.keyOf(Environment.DEFAULT, KEY))).version();
    }

    @Test
    void repairsAValueThatWillNotParse() {
        join(store.compareAndSet(Designations.keyOf(Environment.DEFAULT, KEY),
                CoordinationStore.INITIAL_VERSION, "{\"owner\""));

        final DesignationChange repaired = join(designations.repair(KEY, BLUE, "INC-4821"));

        assertNull(repaired.previous());
        assertTrue(repaired.changed());
        assertEquals(1L, repaired.inForce().seq());
        assertEquals(repaired.inForce(), join(designations.current(KEY)));
        assertFalse(join(designations.repair(KEY, BLUE, null)).changed(), "Readable, it is a designate");
    }

    @Test
    void saysSoOnlyWhenItChangedSomething() {
        final List<String> lines = new ArrayList<>();
        final Designations logged = new Designations(store, time, new TextPiplexObserver(lines::add));

        join(logged.designate(KEY, BLUE, "initial setup"));
        join(logged.designate(KEY, BLUE, "clicked twice"));
        join(logged.designate(KEY, GREEN, "INC-4821"));

        assertEquals(List.of(
                "DESIGNATED environment=default key=eod owner=euc1-blue reason=\"initial setup\"",
                "DESIGNATED environment=default key=eod owner=euc1-green previous=euc1-blue reason=INC-4821"), lines);
    }

    private static <T> T join(final CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
