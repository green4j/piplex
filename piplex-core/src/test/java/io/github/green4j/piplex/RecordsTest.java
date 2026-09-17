/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex;

import io.github.green4j.piplex.exclusive.Designation;
import io.github.green4j.piplex.exclusive.Designations;
import io.github.green4j.piplex.milestone.Milestone;
import io.github.green4j.piplex.milestone.Milestones;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.memory.InMemoryCoordinationStore;
import io.github.green4j.piplex.switches.Switch;
import io.github.green4j.piplex.switches.Switches;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What designations, switches and milestones have in common: a hand-editable JSON record under a key.
 */
class RecordsTest {

    private static final String KEY = "eod";

    private final ManualTime time = new ManualTime();
    private final CoordinationStore store = new InMemoryCoordinationStore(time);

    static List<Kind<?>> kinds() {
        return List.of(
                new Kind<>("piplex/designated/eod", Designations::keyOf, Designation::parse, Designation::toJson,
                        (store, time) -> new Designations(store, time).designate(KEY, "euc1-blue", "INC-4821"),
                        (store, time) -> new Designations(store, time).current(KEY)),
                new Kind<>("piplex/enabled/eod", Switches::keyOf, Switch::parse, Switch::toJson,
                        (store, time) -> new Switches(store, time).disable(KEY, "INC-4821"),
                        (store, time) -> new Switches(store, time).current(KEY)),
                new Kind<>("piplex/milestone/eod", Milestones::keyOf, Milestone::parse, Milestone::toJson,
                        (store, time) -> new Milestones(store, time)
                                .publish(KEY, Generation.of("2026-09-12"), "euc1-blue", "eod#142"),
                        (store, time) -> new Milestones(store, time).current(KEY)));
    }

    @ParameterizedTest
    @MethodSource("kinds")
    void keepsRecordsUnderAPrefixAnOperatorCanType(final Kind<?> kind) {
        assertEquals(kind.storeKey(), kind.keyOf().apply(KEY));
    }

    @ParameterizedTest
    @MethodSource("kinds")
    <T> void survivesARoundTripThroughJson(final Kind<T> kind) {
        join(kind.write().apply(store, time));
        final T written = join(kind.current().apply(store, time));
        assertEquals(written, kind.parse().apply(kind.toJson().apply(written)));
    }

    @ParameterizedTest
    @MethodSource("kinds")
    void refusesWhatIsNotARecordAtAll(final Kind<?> kind) {
        // The shapes a key ends up holding once somebody edits it by hand. The text goes in the
        // message because it is the only copy of what the key held.
        final String cut = "{\"generation\"";
        assertTrue(assertThrows(IllegalArgumentException.class, () -> kind.parse().apply(cut))
                .getMessage().contains(cut));
        assertThrows(IllegalArgumentException.class, () -> kind.parse().apply("[1,2]"));
        assertThrows(IllegalArgumentException.class, () -> kind.parse().apply(""));
    }

    @ParameterizedTest
    @MethodSource("kinds")
    void namesTheKeySomebodyHasToGoAndFixWhenTheValueWillNotParse(final Kind<?> kind) {
        join(store.compareAndSet(kind.storeKey(), CoordinationStore.INITIAL_VERSION, "{\"owner\""));

        for (final BiFunction<CoordinationStore, TimeSource, ? extends CompletionStage<?>> call
                : List.of(kind.current(), kind.write())) {
            final UnreadableKeyException unreadable = assertInstanceOf(UnreadableKeyException.class,
                    assertThrows(CompletionException.class, () -> join(call.apply(store, time))).getCause());
            assertEquals(kind.storeKey(), unreadable.key());
            assertTrue(unreadable.getMessage().contains(kind.storeKey()), unreadable.getMessage());
        }
    }

    @ParameterizedTest
    @MethodSource("kinds")
    void givesUpAfterABoundedNumberOfLostWrites(final Kind<?> kind) {
        final LosingCasStore losing = new LosingCasStore(store);

        final CompletionException thrown = assertThrows(
                CompletionException.class, () -> join(kind.write().apply(losing, time)));
        final ContendedException cause = assertInstanceOf(ContendedException.class, thrown.getCause());
        assertEquals(kind.storeKey(), cause.key());
        assertEquals(8, losing.attempts(), "Bounded, and the bound is the one the classes state");
    }

    private static <T> T join(final CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    /**
     * One kind of record, and how to write and read it.
     *
     * @param storeKey where {@link #KEY} is kept
     * @param keyOf    the class's own spelling of that
     * @param parse    reads the record
     * @param toJson   writes the record
     * @param write    one write of the record to {@link #KEY}
     * @param current  reads what is at {@link #KEY}
     * @param <T>      the record
     */
    record Kind<T>(String storeKey,
                   UnaryOperator<String> keyOf,
                   Function<String, T> parse,
                   Function<T, String> toJson,
                   BiFunction<CoordinationStore, TimeSource, CompletionStage<?>> write,
                   BiFunction<CoordinationStore, TimeSource, CompletionStage<T>> current) {

        @Override
        public String toString() {
            return storeKey;
        }
    }
}
