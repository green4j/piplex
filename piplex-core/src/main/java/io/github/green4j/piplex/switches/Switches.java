/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.switches;

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

    private static final String PREFIX = "piplex/enabled/";
    private static final String OWNER_MARK = "/@";

    private final CoordinationStore store;
    private final TimeSource time;
    private final PiplexObserver observer;

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
        this.store = FailFastStore.of(store, time);
        this.time = Objects.requireNonNull(time, "time");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    /**
     * Where a switch is kept, for an operator reading the store by hand.
     *
     * <p>Every way into this class goes through here, which is why the key is checked here: a blank one
     * does not fail, it names the prefix itself, and then one write drains the whole controller.
     *
     * @param key the switch
     * @return the key it is held at
     * @throws IllegalArgumentException if the key is null or blank
     */
    public static String keyOf(final String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        return PREFIX + key;
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
     */
    public static String ownerKey(final String key, final String ownerId) {
        return key + OWNER_MARK + ownerId;
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
        final String storeKey = keyOf(key);
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
        final String storeKey = keyOf(key);
        return CompareAndSetLoop.write(store, storeKey, entry -> {
            final Switch inForce = overwriteUnreadable
                    ? readableOrNull(storeKey, entry)
                    : switchOf(storeKey, entry);
            if (inForce != null && inForce.enabled() == enabled) {
                return Step.keep(new SwitchChange(inForce, inForce, false));
            }
            final Switch next = new Switch(enabled, reason, time.wallTime());
            return Step.write(next.toJson(), () -> {
                observer.switched(key, enabled, reason);
                return new SwitchChange(inForce, next, true);
            });
        });
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
