/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.example;

import io.github.green4j.piplex.Generation;
import io.github.green4j.piplex.exclusive.Admission;
import io.github.green4j.piplex.exclusive.Admitted;

import java.time.LocalDate;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The small amount of scaffolding the examples need, kept out of them so that what is left is piplex.
 *
 * <p>Blocking on a result is the one thing here that a real host would not do: a Jenkins step hands the
 * stage back to the controller instead, and is called again when the stage completes. An example has no
 * such luxury -- {@code main} ending is the program ending -- so it waits.
 */
final class Examples {

    private static final long WAIT_SECONDS = 30L;

    private Examples() {
    }

    /**
     * @param stage what to wait for
     * @param <T>   what it produces
     * @return its result
     */
    static <T> T await(final CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", interrupted);
        } catch (final ExecutionException failed) {
            throw new IllegalStateException(failed.getCause());
        } catch (final TimeoutException timedOut) {
            throw new IllegalStateException("Gave up after " + WAIT_SECONDS + "s", timedOut);
        }
    }

    /**
     * @param admission what came back
     * @return it, admitted
     * @throws IllegalStateException if it is not -- an example that casts reports a ClassCastException,
     *         which names neither the outcome nor why it was the wrong one
     */
    static Admitted admitted(final Admission admission) {
        if (admission instanceof Admitted held) {
            return held;
        }
        throw new IllegalStateException("Expected to be admitted, got " + admission);
    }

    /**
     * @return today, as the generation a day's work is named by
     */
    static Generation today() {
        return Generation.ofDate(LocalDate.now());
    }

    /**
     * @param text what happened, in the words an operator would use
     */
    static void say(final String text) {
        System.out.println(text);
    }
}
