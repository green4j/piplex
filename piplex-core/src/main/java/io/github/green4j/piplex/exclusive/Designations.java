/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.exclusive;

import io.github.green4j.piplex.ContendedException;
import io.github.green4j.piplex.TimeSource;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.Entry;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The operator's side of a designation: reading who is designated, and naming somebody else.
 *
 * <p>{@link ExclusiveRuns} only ever reads this key. Writing it is an operational act -- "the primary
 * region is Milan now" -- and it is separated from the running side deliberately, because the two have
 * nothing in common but the key: one happens thousands of times without anybody watching, the other a
 * few times a year and always because a person decided something.
 *
 * <p>Writing goes through compare-and-set rather than a plain put, which is what stops two operators
 * acting at once from quietly overwriting each other. It is also idempotent in the way that matters for
 * a job somebody may click twice: designating the owner that is already designated changes nothing and
 * reports {@code changed=false}. Only the owner is compared, so a re-run with a different reason is
 * still a no-op -- the reason describes the change, and there was none.
 *
 * <p>This class does not make the change auditable, and nothing written into the value can. The record
 * is written by the same client it describes. Attribution comes from the door the write went through:
 * an authenticated Jenkins job, whose log holds who pressed the button and when, and -- once discas
 * grows a client audit hook -- the store's own view of which client wrote which key.
 */
public final class Designations {

    private static final String PREFIX = "piplex/designated/";
    private static final int ATTEMPTS = 8;

    private final CoordinationStore store;
    private final TimeSource time;

    /**
     * @param store where designations are kept
     * @param time  where the timestamp in the record comes from
     */
    public Designations(final CoordinationStore store, final TimeSource time) {
        this.store = Objects.requireNonNull(store, "store");
        this.time = Objects.requireNonNull(time, "time");
    }

    /**
     * Where a designation is kept, for an operator reading the store by hand.
     *
     * @param key what is being competed for
     * @return the key it is held at
     */
    public static String keyOf(final String key) {
        return PREFIX + key;
    }

    /**
     * Reads who is designated.
     *
     * @param key what is being competed for
     * @return the record held, or {@code null} when nobody is designated yet
     */
    public CompletionStage<Designation> current(final String key) {
        return store.get(keyOf(key)).thenApply(Designations::designationOf);
    }

    /**
     * Names who may run the work from now on.
     *
     * <p>A run already in flight under the previous owner is stopped by that run's own watch on this
     * key, not from here: this call returns as soon as the new value is in force, while the old holder
     * takes up to its own lease to notice. Nothing here waits for that.
     *
     * @param key      what is being competed for
     * @param owner    who may run it from now on
     * @param by       who is making the change, may be {@code null}
     * @param reason   why, ideally a ticket, may be {@code null}
     * @return what it was and what it is now
     */
    public CompletionStage<DesignationChange> designate(final String key,
                                                        final String owner,
                                                        final String by,
                                                        final String reason) {
        Objects.requireNonNull(owner, "owner");
        return designate(key, owner, by, reason, ATTEMPTS);
    }

    private CompletionStage<DesignationChange> designate(final String key,
                                                         final String owner,
                                                         final String by,
                                                         final String reason,
                                                         final int attemptsLeft) {
        final String storeKey = keyOf(key);
        return store.get(storeKey).thenCompose(entry -> {
            final Designation inForce = designationOf(entry);
            if (inForce != null && inForce.owner().equals(owner)) {
                return CompletableFuture.completedFuture(
                        new DesignationChange(inForce, inForce, false));
            }
            final Designation next = inForce == null
                    ? new Designation(owner, by, reason, time.wallTime(), null, 1L)
                    : inForce.succeededBy(owner, by, reason, time.wallTime());
            return store.compareAndSet(storeKey, entry.version(), next.toJson()).thenCompose(applied -> {
                if (Boolean.TRUE.equals(applied)) {
                    return CompletableFuture.completedFuture(
                            new DesignationChange(inForce, next, true));
                }
                if (attemptsLeft <= 1) {
                    return CompletableFuture.failedFuture(new ContendedException(storeKey, ATTEMPTS));
                }
                return designate(key, owner, by, reason, attemptsLeft - 1);
            });
        });
    }

    private static Designation designationOf(final Entry entry) {
        return entry.exists() ? Designation.parse(entry.value()) : null;
    }
}
