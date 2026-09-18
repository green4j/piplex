/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import io.github.green4j.piplex.Environment;
import io.github.green4j.piplex.TimeSource;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.FailFastStore;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Notices a second live controller calling itself by this controller's owner id.
 *
 * <p>A clone of a {@code JENKINS_HOME} carries the owner id with it, and nothing on disk tells the two
 * apart. So each process writes a mark of its own, made up when it starts, into one key per owner id,
 * and reads it back on the next beat. Somebody else's mark there after this one has written its own is
 * a second process writing to the same key.
 *
 * <p>Only a warning. The lease already keeps two clones from running the same work at once; what is
 * left is an operator who should know that designations naming this owner id name two controllers.
 * Refusing builds on it would turn the moment after a restart -- when the key still holds the mark of
 * the process before -- into an outage, which is why a mark read before this process has written is
 * not counted.
 */
final class OwnerHeartbeat {

    /** How often a controller writes its mark. */
    static final Duration EVERY = Duration.ofSeconds(30);
    /** How long a mark seen once keeps the warning up. */
    static final Duration WARN_FOR = Duration.ofMinutes(10);
    /** How long beats may keep failing to write before the check itself is reported as not working. */
    static final Duration SILENT_AFTER = Duration.ofMinutes(5);

    private static final String KIND = "instances";

    private final String mark = UUID.randomUUID().toString();
    private final TimeSource time;

    // Guarded by this.
    private String ownerId;
    private Environment environment;
    private boolean written;
    private boolean seen;
    private long seenAtNanos;
    // Beats that did not write, since the last one that did: a narrowed ACL refuses every one of them.
    private boolean failing;
    private long failingSinceNanos;
    private long lastFailedNanos;

    /**
     * @param time where time comes from
     */
    OwnerHeartbeat(final TimeSource time) {
        this.time = time;
    }

    static String keyOf(final Environment environment, final String ownerId) {
        return environment.prefixOf(KIND) + ownerId;
    }

    /**
     * Reads the key, notes a mark that is not this process's, and writes this one.
     *
     * @param store   where the key is
     * @param in       the controller's own environment, which is the one its mark goes in
     * @param current the owner id in force now
     * @return completes once the beat is over; never exceptionally
     */
    CompletionStage<Void> tick(final CoordinationStore store, final Environment in, final String current) {
        synchronized (this) {
            if (!current.equals(ownerId) || !in.equals(environment)) {
                // A new owner id is a new key, and so is a new environment: nothing written there yet,
                // and nothing seen. Two controllers sharing an owner id in different environments are
                // two deployments, not one duplicated -- which is the whole point of the segment.
                ownerId = current;
                environment = in;
                written = false;
                seen = false;
            }
        }
        final CoordinationStore failFast = FailFastStore.of(store, time);
        final String key = keyOf(in, current);
        return failFast.get(key)
                .thenCompose(entry -> {
                    synchronized (this) {
                        if (current.equals(ownerId) && written && entry.exists()
                                && !mark.equals(entry.value())) {
                            seen = true;
                            seenAtNanos = time.nanos();
                        }
                    }
                    return failFast.compareAndSet(key, entry.version(), mark);
                })
                .handle((swapped, error) -> {
                    // A lost compare is somebody else's write, which the next beat reads; a failure is a
                    // store the next beat asks again. Either way, one that never ends is reported.
                    wrote(in, current, Boolean.TRUE.equals(swapped));
                    return (Void) null;
                });
    }

    private synchronized void wrote(final Environment in, final String current, final boolean swapped) {
        if (!current.equals(ownerId) || !in.equals(environment)) {
            return;
        }
        if (swapped) {
            written = true;
            failing = false;
            return;
        }
        final long now = time.nanos();
        if (!failing) {
            failing = true;
            failingSinceNanos = now;
        }
        lastFailedNanos = now;
    }

    /**
     * @return the owner id whose mark has not been written for a while, or {@code null}. Only while
     *         beats are still being attempted: a controller with no store configured is not silent
     */
    synchronized String silent() {
        if (!failing
                || !time.deadlinePassed(failingSinceNanos + SILENT_AFTER.toNanos())
                || time.deadlinePassed(lastFailedNanos + EVERY.multipliedBy(2).toNanos())) {
            return null;
        }
        return ownerId;
    }

    /**
     * @return the owner id another controller was seen using lately, or {@code null}
     */
    synchronized String duplicated() {
        if (!seen || time.deadlinePassed(seenAtNanos + WARN_FOR.toNanos())) {
            return null;
        }
        return ownerId;
    }
}
