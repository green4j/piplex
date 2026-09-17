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
 * Linearizable key state, bounded change waits and fenced leases required by piplex.
 *
 * <p>All operations are asynchronous and must complete within {@link #responseBound()}, with
 * {@code maxWait} added for change waits. A failed stage may represent an unknown outcome. Retrying a
 * version-fenced {@link #compareAndSet} is safe; other operations require their documented recovery.
 *
 * <p>Primitives wrap implementations in {@link FailFastStore}, converting synchronous failures and
 * non-completing stages into failed stages.
 */
public interface CoordinationStore extends AutoCloseable {

    /**
     * The version meaning "before anything": what to pass when a key has never been read, and what
     * {@link #compareAndSet} expects in order to create a key which must not already exist.
     */
    String INITIAL_VERSION = "";

    /**
     * What {@link #responseBound()} is unless a store says otherwise.
     */
    Duration DEFAULT_RESPONSE_BOUND = Duration.ofMinutes(5);

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
     * <p>A store which could not reach the key at the end of the wait may answer with the newest state
     * it saw before that, rather than failing: the caller asked to be told within {@code maxWait}, and
     * an outage that lifts inside the budget is the store's to absorb. What it returns is then
     * {@link Entry#confirmed() unconfirmed}, and it can be a whole wait old. A caller which only wants
     * to know when to look again may ignore that; one which counts how long it has been since it last
     * learnt anything about the key must treat it as having learnt nothing.
     *
     * @param key          the key
     * @param sinceVersion the version already seen, or {@link #INITIAL_VERSION} to return as soon as
     *                     the key holds anything
     * @param maxWait      how long to wait before returning the current state unchanged
     * @return the state in force when the wait ended, or the newest seen and marked unconfirmed
     */
    CompletionStage<Entry> awaitChange(String key, String sinceVersion, Duration maxWait);

    /**
     * Waits for a key to change, saying how soon the caller must hear of it.
     *
     * <p>A store whose watches all cost the same need not tell the two apart, and by default this is
     * {@link #awaitChange(String, String, Duration)}. A store which wraps another must pass the hint on.
     *
     * @param key          the key
     * @param sinceVersion as for {@link #awaitChange(String, String, Duration)}
     * @param maxWait      as for {@link #awaitChange(String, String, Duration)}
     * @param watch        how soon the caller must hear of a change
     * @return as for {@link #awaitChange(String, String, Duration)}
     */
    default CompletionStage<Entry> awaitChange(final String key,
                                               final String sinceVersion,
                                               final Duration maxWait,
                                               final Watch watch) {
        return awaitChange(key, sinceVersion, maxWait);
    }

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
     *         caller must stop treating itself as the holder. A failed stage is neither: it says the
     *         outcome could not be learnt, which is what a holder tolerates for its renewal grace
     */
    CompletionStage<Boolean> renew(String key, LeaseHandle handle, Duration ttl);

    /**
     * Gives up a lease. Releasing one already lapsed or released is not an error.
     *
     * @param key    the key the lease is on
     * @param handle the handle acquisition returned
     * @return completion; a failed stage says the lease may still be standing, and the next run waits
     *         it out
     */
    CompletionStage<Void> release(String key, LeaseHandle handle);

    /**
     * How long a stage of this store may take, past the wait asked for. It is a backstop against a
     * stage that never completes, not a timeout to tune: set it above the longest a call can
     * legitimately take, retries included.
     *
     * @return a positive duration; five minutes unless the store says otherwise
     */
    default Duration responseBound() {
        return DEFAULT_RESPONSE_BOUND;
    }

    @Override
    void close();
}
