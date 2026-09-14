/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.switches;

import io.github.green4j.piplex.ContendedException;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.Entry;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The operator's side of a switch: reading whether work may run, and turning it off or back on.
 *
 * <p>Turning work off is what an operator does during an incident or a migration, and today that is
 * done by commenting a schedule out of one deployment's configuration and redeploying it by hand. One
 * write here does the same thing, everywhere, in a second -- and, unlike the redeploy, it also stops a
 * run already in flight, through the same machinery that stops one whose owner changed.
 *
 * <p>An absent key means on. Switching off is always the deliberate act, so a store that has never been
 * written to, or one whose key somebody deleted, does not silently stop the work.
 *
 * <p>The record carries the reason and nothing else. Who flipped it and when belong to the door the
 * write went through -- the log of the job that made it -- not to a value the same client could write
 * anything into.
 */
public final class Switches {

    private static final String PREFIX = "piplex/enabled/";
    private static final int ATTEMPTS = 8;

    private final CoordinationStore store;

    /**
     * @param store where switches are kept
     */
    public Switches(final CoordinationStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Where a switch is kept, for an operator reading the store by hand.
     *
     * @param key the switch; a key of the form {@code <work>/<owner>} is how one deployment alone is
     *            drained, and is a plain key like any other
     * @return the key it is held at
     */
    public static String keyOf(final String key) {
        return PREFIX + key;
    }

    /**
     * Reads a switch.
     *
     * @param key the switch
     * @return what is in force, {@link Switch#ENABLED} when the key holds nothing
     */
    public CompletionStage<Switch> current(final String key) {
        return store.get(keyOf(key)).thenApply(Switches::switchOf);
    }

    /**
     * Lets the work run again.
     *
     * @param key the switch
     * @return what is in force afterwards
     */
    public CompletionStage<Switch> enable(final String key) {
        return set(key, Switch.ENABLED, ATTEMPTS);
    }

    /**
     * Stops the work, and stops any run of it already in flight.
     *
     * @param key    the switch
     * @param reason why, which is what the stopped runs will say in their logs
     * @return what is in force afterwards
     */
    public CompletionStage<Switch> disable(final String key, final String reason) {
        return set(key, new Switch(false, reason), ATTEMPTS);
    }

    private CompletionStage<Switch> set(final String key, final Switch desired, final int attemptsLeft) {
        final String storeKey = keyOf(key);
        return store.get(storeKey).thenCompose(entry -> {
            final Switch inForce = switchOf(entry);
            if (inForce.equals(desired)) {
                return CompletableFuture.completedFuture(inForce);
            }
            return store.compareAndSet(storeKey, entry.version(), desired.toJson()).thenCompose(applied -> {
                if (Boolean.TRUE.equals(applied)) {
                    return CompletableFuture.completedFuture(desired);
                }
                if (attemptsLeft <= 1) {
                    return CompletableFuture.failedFuture(new ContendedException(storeKey, ATTEMPTS));
                }
                return set(key, desired, attemptsLeft - 1);
            });
        });
    }

    private static Switch switchOf(final Entry entry) {
        return entry.exists() ? Switch.parse(entry.value()) : Switch.ENABLED;
    }
}
