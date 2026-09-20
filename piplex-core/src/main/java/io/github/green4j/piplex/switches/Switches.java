/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.switches;

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
 * Reads and changes shared operational switches.
 *
 * <p>An absent key means enabled. Disabling a switch also revokes admitted runs that watch it. Writing
 * the state already in force is a no-op and preserves its reason.
 */
public final class Switches {

    private static final String KIND = "enabled";
    private static final String OWNER_MARK = "/@";

    private final CoordinationStore store;
    private final TimeSource time;
    private final PiplexObserver observer;
    private final Environment environment;
    private final String prefix;

    /**
     * @param store where switches are kept
     * @param time  what times the store's answers and stamps the record
     */
    public Switches(final CoordinationStore store, final TimeSource time) {
        this(store, time, PiplexObserver.NONE);
    }

    /**
     * @param store    where switches are kept
     * @param time     what times the store's answers and stamps the record
     * @param observer told when a switch is flipped
     */
    public Switches(final CoordinationStore store, final TimeSource time, final PiplexObserver observer) {
        this(store, time, observer, Environment.DEFAULT);
    }

    /**
     * @param store       where switches are kept
     * @param time        what times the store's answers and stamps the record
     * @param observer    told when a switch is flipped
     * @param environment which set of orchestrations these switches belong to
     */
    public Switches(final CoordinationStore store,
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
     * Where a switch is kept, for an operator reading the store by hand.
     *
     * @param environment which set of orchestrations it belongs to
     * @param key         the switch
     * @return the key it is held at
     * @throws IllegalArgumentException if the key is null or blank
     */
    public static String keyOf(final Environment environment, final String key) {
        Objects.requireNonNull(environment, "environment");
        return environment.prefixOf(KIND) + checked(key);
    }

    /**
     * Every way into this class goes through this check: a blank key does not fail, it names the
     * prefix itself, and then one write drains the whole controller.
     *
     * @param key the switch
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
     * The switch that drains one deployment alone.
     *
     * <p>A run which consults {@code key} consults this one too, under its own owner, and goes on only
     * while both are on. The {@code @} keeps it apart from the work switches under {@code key}: a
     * request may not consult a switch with a segment starting with one.
     *
     * @param key     the switch the work consults
     * @param ownerId the deployment
     * @return {@code <key>/@<ownerId>}, to pass wherever a switch is named
     * @throws IllegalArgumentException if either is null or blank
     */
    public static String ownerKey(final String key, final String ownerId) {
        // Either one missing composes a key that reads as a real one and drains nobody: a blank owner
        // gives "<key>/@", which no run consults and an operator takes for a drain in force.
        return checked(key) + OWNER_MARK + checkedOwner(ownerId);
    }

    /**
     * @param ownerId the deployment a per-owner switch is for
     * @return it, once it is worth composing a key from
     * @throws IllegalArgumentException if it is null or blank
     */
    private static String checkedOwner(final String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("ownerId must not be blank");
        }
        return ownerId;
    }

    /**
     * @param key a switch a request consults
     * @return whether it has a segment only {@link #ownerKey} may write
     */
    public static boolean namesAnOwner(final String key) {
        return key.startsWith("@") || key.contains(OWNER_MARK);
    }

    /**
     * Reads a switch.
     *
     * @param key the switch
     * @return what is in force, {@link Switch#ENABLED} when the key holds nothing
     */
    public CompletionStage<Switch> current(final String key) {
        final String storeKey = storeKeyOf(key);
        return store.get(storeKey).thenApply(entry -> switchOf(storeKey, entry));
    }

    /**
     * Lets the work run again.
     *
     * @param key the switch
     * @return what it was and what it is now
     */
    public CompletionStage<SwitchChange> enable(final String key) {
        return set(key, true, null, false);
    }

    /**
     * Stops the work, and stops any run of it already in flight.
     *
     * @param key    the switch
     * @param reason why, which is what the stopped runs will say in their logs
     * @return what it was and what it is now
     */
    public CompletionStage<SwitchChange> disable(final String key, final String reason) {
        return set(key, false, reason, false);
    }

    /**
     * Sets a switch as {@link #enable} and {@link #disable} do, and also replaces a value that does not
     * parse.
     *
     * @param key     the switch
     * @param enabled whether the work may run
     * @param reason  why it is switched off, may be {@code null}
     * @return what it was and what it is now
     */
    public CompletionStage<SwitchChange> repair(final String key, final boolean enabled, final String reason) {
        return set(key, enabled, reason, true);
    }

    private CompletionStage<SwitchChange> set(final String key,
                                              final boolean enabled,
                                              final String reason,
                                              final boolean overwriteUnreadable) {
        final String storeKey = storeKeyOf(key);
        return CompareAndSetLoop.write(store, storeKey, entry -> {
            final Switch inForce = overwriteUnreadable
                    ? readableOrNull(storeKey, entry)
                    : switchOf(storeKey, entry);
            if (inForce != null && inForce.enabled() == enabled) {
                return Step.keep(new SwitchChange(inForce, inForce, false));
            }
            final Switch next = new Switch(enabled, reason, time.wallTime());
            return Step.write(next.toJson(), () -> {
                observer.switched(environment, key, enabled, reason);
                return new SwitchChange(inForce, next, true);
            });
        });
    }

    private String storeKeyOf(final String key) {
        return prefix + checked(key);
    }

    private static Switch readableOrNull(final String storeKey, final Entry entry) {
        try {
            return switchOf(storeKey, entry);
        } catch (final UnreadableKeyException overwritten) {
            return null;
        }
    }

    private static Switch switchOf(final String storeKey, final Entry entry) {
        if (!entry.exists()) {
            return Switch.ENABLED;
        }
        try {
            return Switch.parse(entry.value());
        } catch (final RuntimeException notReadable) {
            throw new UnreadableKeyException(storeKey, "switch", notReadable);
        }
    }
}
