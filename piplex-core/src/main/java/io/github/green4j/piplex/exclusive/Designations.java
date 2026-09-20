/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.exclusive;

import io.github.green4j.piplex.Environment;
import io.github.green4j.piplex.TimeSource;
import io.github.green4j.piplex.UnreadableKeyException;
import io.github.green4j.piplex.observe.PiplexObserver;
import io.github.green4j.piplex.store.CompareAndSetLoop;
import io.github.green4j.piplex.store.CompareAndSetLoop.Step;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.FailFastStore;
import io.github.green4j.piplex.store.Entry;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Reads and changes the owner designated to run work.
 *
 * <p>Writes use compare-and-set. Designating the owner already in force is a no-op and preserves the
 * existing reason; the record is explanatory state, not an audit trail.
 */
public final class Designations {

    private static final String KIND = "designated";

    private final CoordinationStore store;
    private final TimeSource time;
    private final PiplexObserver observer;
    private final Environment environment;
    private final String prefix;

    /**
     * @param store where designations are kept
     * @param time  where the timestamp in the record comes from
     */
    public Designations(final CoordinationStore store, final TimeSource time) {
        this(store, time, PiplexObserver.NONE);
    }

    /**
     * @param store    where designations are kept
     * @param time     where the timestamp in the record comes from
     * @param observer told when somebody else is designated
     */
    public Designations(final CoordinationStore store,
                        final TimeSource time,
                        final PiplexObserver observer) {
        this(store, time, observer, Environment.DEFAULT);
    }

    /**
     * @param store       where designations are kept
     * @param time        where the timestamp in the record comes from
     * @param observer    told when somebody else is designated
     * @param environment which set of orchestrations these designations belong to
     */
    public Designations(final CoordinationStore store,
                        final TimeSource time,
                        final PiplexObserver observer,
                        final Environment environment) {
        this.store = FailFastStore.of(store, time);
        this.time = Objects.requireNonNull(time, "time");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.prefix = environment.prefixOf(KIND);
    }

    /**
     * Where a designation is kept, for an operator reading the store by hand.
     *
     * @param environment which set of orchestrations it belongs to
     * @param key         what is being competed for
     * @return the key it is held at
     * @throws IllegalArgumentException if the key is null or blank
     */
    public static String keyOf(final Environment environment, final String key) {
        Objects.requireNonNull(environment, "environment");
        return environment.prefixOf(KIND) + checked(key);
    }

    /**
     * Every way into this class goes through this check: a blank key does not fail, it names the
     * prefix itself, and then one designation speaks for every key.
     *
     * @param key what is being competed for
     * @return it, once it is worth composing a key from
     * @throws IllegalArgumentException if the key is null or blank
     */
    private static String checked(final String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        return key;
    }

    /**
     * The other half of the same check: a designation naming nobody is held at the key like any other,
     * and every candidate reads it and stands down. One blank string stops the work everywhere and
     * looks, in the record, exactly like a deliberate handover.
     *
     * @param owner who may run the work
     * @return it, once it names somebody
     * @throws IllegalArgumentException if the owner is null or blank
     */
    private static String checkedOwner(final String owner) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner must not be blank");
        }
        return owner;
    }

    /**
     * Reads who is designated.
     *
     * @param key what is being competed for
     * @return the record held, or {@code null} when nobody is designated yet
     */
    public CompletionStage<Designation> current(final String key) {
        final String storeKey = storeKeyOf(key);
        return store.get(storeKey).thenApply(entry -> designationOf(storeKey, entry));
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
     * @param reason   why, ideally a ticket, may be {@code null}
     * @return what it was and what it is now
     */
    public CompletionStage<DesignationChange> designate(final String key,
                                                        final String owner,
                                                        final String reason) {
        return designate(key, checkedOwner(owner), reason, false);
    }

    /**
     * Designates as {@link #designate} does, and also replaces a value that does not parse.
     *
     * <p>The replaced value is reported as nobody designated, and the record starts over at
     * {@code seq} 1.
     *
     * @param key    what is being competed for
     * @param owner  who may run it from now on
     * @param reason why, may be {@code null}
     * @return what it was and what it is now
     */
    public CompletionStage<DesignationChange> repair(final String key, final String owner, final String reason) {
        return designate(key, checkedOwner(owner), reason, true);
    }

    private CompletionStage<DesignationChange> designate(final String key,
                                                         final String owner,
                                                         final String reason,
                                                         final boolean overwriteUnreadable) {
        final String storeKey = storeKeyOf(key);
        return CompareAndSetLoop.write(store, storeKey, entry -> {
            final Designation inForce = overwriteUnreadable
                    ? readableOrNull(storeKey, entry)
                    : designationOf(storeKey, entry);
            if (inForce != null && inForce.owner().equals(owner)) {
                return Step.keep(new DesignationChange(inForce, inForce, false));
            }
            final Designation next = inForce == null
                    ? new Designation(owner, reason, time.wallTime(), null, 1L)
                    : inForce.succeededBy(owner, reason, time.wallTime());
            return Step.write(next.toJson(), () -> {
                observer.designated(
                        environment, key, owner, inForce == null ? null : inForce.owner(), reason);
                return new DesignationChange(inForce, next, true);
            });
        });
    }

    private String storeKeyOf(final String key) {
        return prefix + checked(key);
    }

    private static Designation readableOrNull(final String storeKey, final Entry entry) {
        try {
            return designationOf(storeKey, entry);
        } catch (final UnreadableKeyException overwritten) {
            return null;
        }
    }

    private static Designation designationOf(final String storeKey, final Entry entry) {
        if (!entry.exists()) {
            return null;
        }
        try {
            return Designation.parse(entry.value());
        } catch (final RuntimeException notReadable) {
            throw new UnreadableKeyException(storeKey, "designation", notReadable);
        }
    }
}
