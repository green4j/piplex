/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.exclusive;

import io.github.green4j.piplex.ContendedException;
import io.github.green4j.piplex.LosingCasStore;
import io.github.green4j.piplex.ManualTime;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.memory.InMemoryCoordinationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
    void hasNobodyDesignatedUntilSomebodyIs() {
        // Null and not a record standing for nobody: "not designated yet" and "designated to nobody"
        // would otherwise be the same value, and only the first of them is a state this has.
        assertNull(join(designations.current(KEY)));
    }

    @Test
    void namesTheFirstOwnerWithNobodyBeforeThem() {
        final DesignationChange change = join(designations.designate(KEY, BLUE, "ops", "INC-4821"));

        assertTrue(change.changed());
        assertNull(change.previous());
        final Designation now = change.inForce();
        assertEquals(BLUE, now.owner());
        assertEquals("ops", now.by());
        assertEquals("INC-4821", now.reason());
        assertNull(now.prev());
        assertEquals(1L, now.seq());
    }

    @Test
    void recordsWhoHeldItBeforeAndCountsTheChange() {
        join(designations.designate(KEY, BLUE, "ops", "INC-4821"));
        final DesignationChange change = join(designations.designate(KEY, GREEN, "ops", "INC-4830"));

        assertTrue(change.changed());
        assertEquals(BLUE, change.previous().owner());
        assertEquals(GREEN, change.inForce().owner());
        assertEquals(BLUE, change.inForce().prev());
        assertEquals(2L, change.inForce().seq());
    }

    @Test
    void designatingTheOwnerAlreadyInForceChangesNothing() {
        join(designations.designate(KEY, BLUE, "ops", "INC-4821"));
        final String versionAfterFirst = version();

        final DesignationChange again = join(designations.designate(KEY, BLUE, "somebody-else", "INC-4830"));

        assertFalse(again.changed());
        assertEquals(1L, again.inForce().seq(), "the counter says how many times it moved, and it did not");
        assertEquals("INC-4821", again.inForce().reason(), "the reason it is in force for is the first one");
        assertEquals(versionAfterFirst, version(), "and the key was not rewritten at all");
    }

    @Test
    void stampsTheRecordFromTheTimeSourceAndNotTheSystemClock() {
        time.advance(Duration.ofHours(3L));
        final Designation now = join(designations.designate(KEY, BLUE, "ops", null)).inForce();
        assertEquals(time.wallTime(), now.at());
    }

    @Test
    void refusesToDesignateNobody() {
        assertThrows(NullPointerException.class, () -> designations.designate(KEY, null, "ops", "INC-4821"));
        assertNull(join(designations.current(KEY)), "and it never reached the store");
    }

    @Test
    void keepsDesignationsUnderAPrefixAnOperatorCanType() {
        assertEquals("piplex/designated/eod", Designations.keyOf(KEY));
    }

    @Test
    void survivesARoundTripThroughJson() {
        final Designation written = join(designations.designate(KEY, BLUE, "ops", "INC-4821")).inForce();
        assertEquals(written, Designation.parse(written.toJson()));
    }

    @Test
    void refusesARecordWithNoOwnerInIt() {
        // Keys here are read and edited by people, so a half-written record has to be refused rather
        // than read as a designation naming nobody. It is refused -- though only as a bare
        // IllegalStateException from the JSON layer, which names neither the key nor the text.
        assertThrows(RuntimeException.class, () -> Designation.parse("{\"seq\":1}"));
        assertThrows(RuntimeException.class, () -> Designation.parse("{\"owner\":\"blue\",\"at\":\"nope\"}"));
    }

    @Test
    void readsAHandWrittenRecordWithNoCounterAsZero() {
        // Worth pinning because it is not obvious and it is load-bearing: seq is optional, so a record
        // typed in by hand starts the counter at zero and the next change makes it one -- the same
        // value a first designation through designate() would have had.
        final Designation read = Designation.parse("{\"owner\":\"euc1-blue\"}");
        assertEquals(BLUE, read.owner());
        assertEquals(0L, read.seq());
        assertNull(read.at());
    }

    @Test
    void givesUpAfterABoundedNumberOfLostWrites() {
        final LosingCasStore losing = new LosingCasStore(store);
        final Designations contended = new Designations(losing, time);

        final CompletionException thrown = assertThrows(
                CompletionException.class, () -> join(contended.designate(KEY, BLUE, "ops", "INC-4821")));
        final ContendedException cause = assertInstanceOf(ContendedException.class, thrown.getCause());
        assertEquals(Designations.keyOf(KEY), cause.key());
        assertEquals(8, losing.attempts(), "bounded, and the bound is the one the class states");
    }

    private String version() {
        return join(store.get(Designations.keyOf(KEY))).version();
    }

    @Test
    void refusesWhatIsNotARecordAtAll() {
        // The shapes a key ends up holding once somebody edits it by hand. The text goes in the
        // message because it is the only copy of what the key held.
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Designation.parse("{\"owner\""))
                .getMessage().contains("{\"owner\""));
        assertThrows(IllegalArgumentException.class, () -> Designation.parse("[1,2]"));
        assertThrows(IllegalArgumentException.class, () -> Designation.parse(""));
    }

    private static <T> T join(final CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
