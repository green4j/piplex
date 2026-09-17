/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.store;

import io.github.green4j.piplex.ContendedException;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Read, decide, compare-and-set, and on a lost compare read again: how every piplex record is written.
 *
 * <p>Losses are bounded by {@link #ATTEMPTS}, after which the write fails with {@link ContendedException}.
 */
public final class CompareAndSetLoop {

    /**
     * How many times a write is tried before it is given up.
     */
    public static final int ATTEMPTS = 8;

    private CompareAndSetLoop() {
    }

    /**
     * What to do with the value read.
     *
     * @param value  what to write, or {@code null} to write nothing
     * @param result the answer, asked for only once the write is done or found unnecessary
     * @param <T>    the answer's type
     */
    public record Step<T>(String value, Supplier<T> result) {

        /**
         * @param result the answer
         * @param <T>    the answer's type
         * @return a step which writes nothing
         */
        public static <T> Step<T> keep(final T result) {
            return new Step<>(null, () -> result);
        }

        /**
         * @param value  what to write
         * @param result the answer, asked for once the write is applied
         * @param <T>    the answer's type
         * @return a step which writes
         */
        public static <T> Step<T> write(final String value, final Supplier<T> result) {
            return new Step<>(Objects.requireNonNull(value, "value"), result);
        }
    }

    /**
     * Writes one key.
     *
     * @param store  where the key is kept
     * @param key    the key
     * @param decide what to do with what was read, asked again after every lost compare
     * @param <T>    the answer's type
     * @return the answer of the step which was applied
     */
    public static <T> CompletionStage<T> write(final CoordinationStore store,
                                               final String key,
                                               final Function<Entry, Step<T>> decide) {
        return attempt(store, key, decide, ATTEMPTS);
    }

    private static <T> CompletionStage<T> attempt(final CoordinationStore store,
                                                  final String key,
                                                  final Function<Entry, Step<T>> decide,
                                                  final int attemptsLeft) {
        return store.get(key).thenCompose(entry -> {
            final Step<T> step = decide.apply(entry);
            if (step.value() == null) {
                return CompletableFuture.completedFuture(step.result().get());
            }
            return store.compareAndSet(key, entry.version(), step.value()).thenCompose(applied -> {
                if (Boolean.TRUE.equals(applied)) {
                    return CompletableFuture.completedFuture(step.result().get());
                }
                if (attemptsLeft <= 1) {
                    return CompletableFuture.failedFuture(new ContendedException(key, ATTEMPTS));
                }
                return attempt(store, key, decide, attemptsLeft - 1);
            });
        });
    }
}
