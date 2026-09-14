/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.store;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * The small amount of shared state piplex needs, and nothing more: a linearizable compare-and-set per
 * key, a way to wait for a key to change, and a lease with a fencing token.
 *
 * <p>Every coordination store worth using offers these -- discas, etcd, Consul, ZooKeeper -- so keeping
 * the surface this narrow is what lets the engine be written once and tested without a cluster.
 *
 * <p>Implementations are <b>constructed explicitly</b> and handed to the primitives. There is no
 * discovery, no {@code ServiceLoader}, and nothing on the classpath decides which one is in use:
 *
 * <pre>{@code
 * CoordinationStore store = new DiscasCoordinationStore(client);
 * ExclusiveRuns runs = new ExclusiveRuns(store, TimeSource.of(scheduler));
 * }</pre>
 *
 * <p>Everything is asynchronous because the stores underneath are. Nothing here blocks, and completions
 * may run on a store-owned thread -- hop off it before doing work of any length.
 *
 * <p><b>On failure.</b> A failed stage means the operation may or may not have taken effect unless the
 * implementation documents otherwise. A version-fenced {@link #compareAndSet} is safe to re-send under
 * an unknown outcome, because a stale expected version cannot apply twice. Nothing else here is.
 */
public interface CoordinationStore extends AutoCloseable {

    /**
     * The version meaning "before anything": what to pass when a key has never been read, and what
     * {@link #compareAndSet} expects in order to create a key which must not already exist.
     */
    String INITIAL_VERSION = "";

    /**
     * Reads a key.
     *
     * @param key the key
     * @return the state in force, with a version even when the key holds no value
     */
    CompletionStage<Entry> get(String key);

    /**
     * Writes a value only if the key still holds the version expected.
     *
     * <p>Writing the value a key already holds may report success <b>without advancing the version and
     * without waking anybody waiting</b>. Nothing should depend on a write being observable; decide from
     * what the value is, never from the fact that a write happened.
     *
     * @param key             the key
     * @param expectedVersion the version the caller last saw, or {@link #INITIAL_VERSION} to require
     *                        that the key does not yet exist
     * @param value           the value to write
     * @return {@code true} when the key holds what was asked for, {@code false} when the compare was lost
     */
    CompletionStage<Boolean> compareAndSet(String key, String expectedVersion, String value);

    /**
     * Waits until a key moves past the version given, or until the wait runs out.
     *
     * <p>This is a query, not a subscription, and it <b>coalesces</b>: several writes during the wait
     * report the latest state, and the ones in between are simply not seen.
     *
     * <p>That is not a limitation to work around. Everything in piplex which waits asks a question about
     * state -- "am I still the owner", "has it reached this generation" -- so a change that went by
     * unobserved costs nothing, and where a value flipped away and back the coalesced answer is the one
     * worth acting on. It is also what makes a restarted caller correct: it holds no position of its own
     * to lose. Callers must compare state; none of them may count events.
     *
     * <p>Waiting is asked of the store rather than spun above it for two plain reasons. A store may
     * already implement precisely this, and then a caller looping on {@link #get} would be throwing that
     * away and rebuilding it worse. And how long to leave between looks is a question about what a read
     * costs -- a consensus round in one store, a cheap lookup in another -- which is the store's to
     * answer and not this interface's.
     *
     * <p>It does not remove the loop from the caller: whatever waits still re-reads and compares after
     * every return. It makes each turn of that loop the best the store can do.
     *
     * @param key          the key
     * @param sinceVersion the version already seen, or {@link #INITIAL_VERSION} to return as soon as
     *                     the key holds anything
     * @param maxWait      how long to wait before returning the current state unchanged
     * @return the state in force when the wait ended
     */
    CompletionStage<Entry> awaitChange(String key, String sinceVersion, Duration maxWait);

    /**
     * Asks for the lease on a key without waiting for it.
     *
     * @param key     the key the lease is taken on; keep leases under a prefix of their own, since a
     *                lease record and a value in the same key are a collision
     * @param ownerId who is asking; it must name exactly one holder, because it is the only thing
     *                available to recognise a lease taken by an attempt whose outcome was never learned
     * @param ttl     how long the lease lasts unless renewed
     * @return what happened
     */
    CompletionStage<LeaseAttempt> tryAcquire(String key, String ownerId, Duration ttl);

    /**
     * Extends a lease still held.
     *
     * @param key    the key the lease is on
     * @param handle the handle acquisition returned
     * @param ttl    how much longer it should last
     * @return {@code true} when extended; {@code false} when the lease was lapsed or taken, and the
     *         caller must stop treating itself as the holder
     */
    CompletionStage<Boolean> renew(String key, LeaseHandle handle, Duration ttl);

    /**
     * Gives up a lease. Releasing one already lapsed or released is not an error.
     *
     * @param key    the key the lease is on
     * @param handle the handle acquisition returned
     * @return completion
     */
    CompletionStage<Void> release(String key, LeaseHandle handle);

    @Override
    void close();
}
