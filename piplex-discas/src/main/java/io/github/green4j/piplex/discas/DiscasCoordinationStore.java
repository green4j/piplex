/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.discas;

import io.github.green4j.discas.client.DisCasClient;
import io.github.green4j.discas.client.GetResult;
import io.github.green4j.discas.client.Version;
import io.github.green4j.discas.client.WatchResult;
import io.github.green4j.discas.common.client.ReadConsistency;
import io.github.green4j.discas.client.lock.Lock;
import io.github.green4j.discas.client.lock.LockAcquireResult;
import io.github.green4j.discas.client.lock.LockInfo;
import io.github.green4j.discas.client.lock.LockToken;
import io.github.green4j.discas.client.lock.LockWriteStatus;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.Entry;
import io.github.green4j.piplex.store.LeaseAttempt;
import io.github.green4j.piplex.store.LeaseHandle;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * {@link CoordinationStore} over a discas cluster.
 *
 * <p>Constructed explicitly around a client the caller already made and already owns:
 *
 * <pre>{@code
 * DisCasClient client = DisCasClientFactory.create(ClientId.of("jenkins-euc1-blue"), bootstrap);
 * CoordinationStore store = new DiscasCoordinationStore(client);
 * }</pre>
 *
 * <p><b>Give every controller its own {@code ClientId}, and turn authentication on.</b> Two things
 * follow from discas' own documentation and both matter here. A shared HTTP agent speaks with the
 * agent's identity, so four controllers behind agents collapse into one identity and nothing can be
 * attributed to any of them -- which is why this adapter takes the Java client rather than the agent.
 * And in the default {@code AllowAll} mode a client id is merely claimed, not checked, so any record of
 * who did what is worth no more than the claim until TOKEN or mTLS is switched on.
 *
 * <p><b>discas 0.0.2 or later.</b> Not a preference: 0.0.2 is where an acquire whose fenced write lost
 * to somebody who left the key free answers {@code NOT_HELD} rather than naming a holder it has not
 * got, and this adapter reads that answer literally. Against 0.0.1 the same moment arrives as a lock
 * held by nobody, which is refused here rather than guessed at. The two cannot be mixed anyway -- the
 * client and the nodes must be the same version, because {@code CLIENT_HELLO} changed shape in 0.0.2
 * without the protocol version changing with it.
 */
public final class DiscasCoordinationStore implements CoordinationStore {

    private final DisCasClient client;
    private final ReadConsistency watchConsistency;
    private final Duration watchPollPeriod;
    private final boolean ownsClient;

    /**
     * Wraps a client whose lifecycle stays with the caller. A discas client is meant to be long-lived
     * and shared, so this is the usual form.
     *
     * @param client the client
     */
    public DiscasCoordinationStore(final DisCasClient client) {
        this(client, ReadConsistency.LINEARIZABLE, false);
    }

    /**
     * Wraps a client, saying how watches read and who closes it.
     *
     * <p>Watches read {@link ReadConsistency#LINEARIZABLE} by default, and deliberately: the value a
     * watch returns is <b>acted on</b> here -- a changed designation revokes a run in flight -- so a
     * stale read would stop the wrong one. Each poll is then a consensus round, which for the handful
     * of keys piplex watches is a round a second and nothing to worry about. Drop it to
     * {@link ReadConsistency#SERIALIZABLE} only if the number of parked waiters ever makes that untrue,
     * and accept being a moment late.
     *
     * @param client           the client
     * @param watchConsistency how {@link #awaitChange} reads
     * @param ownsClient       whether {@link #close()} should close the client too. A discas client is
     *                         meant to be long-lived and shared, so normally it should not
     */
    public DiscasCoordinationStore(final DisCasClient client,
                                   final ReadConsistency watchConsistency,
                                   final boolean ownsClient) {
        this(client, watchConsistency, null, ownsClient);
    }

    /**
     * Wraps a client, saying how watches read, how often they poll, and who closes it.
     *
     * <p>A watch in discas is a poll, not a subscription, and {@code watchPollPeriod} is how long the
     * client waits after one poll answered before making the next -- the gap actually taken is spread
     * up to five times it. Left unset, the client's own setting decides, and that is the right default
     * for two reasons: the client-wide setting is the only one allowed below
     * {@link DisCasClient#MIN_WATCH_POLL_PERIOD}, and passing a period from here unconditionally would
     * quietly override an operator who had deliberately made that whole-client decision.
     *
     * <p>Set it when the number of watches makes their cost worth governing from the store rather than
     * from the client. There are more of them than there look: a parked candidate races three at once,
     * and an admitted run holds up to two more for as long as it runs. At
     * {@link ReadConsistency#LINEARIZABLE} every poll of every one of them is a consensus round.
     *
     * @param client           the client
     * @param watchConsistency how {@link #awaitChange} reads
     * @param watchPollPeriod  the shortest gap between polls of a watch, at least
     *                         {@link DisCasClient#MIN_WATCH_POLL_PERIOD}, or {@code null} to leave it
     *                         to the client's own configuration
     * @param ownsClient       whether {@link #close()} should close the client too. A discas client is
     *                         meant to be long-lived and shared, so normally it should not
     */
    public DiscasCoordinationStore(final DisCasClient client,
                                   final ReadConsistency watchConsistency,
                                   final Duration watchPollPeriod,
                                   final boolean ownsClient) {
        this.client = Objects.requireNonNull(client, "client");
        this.watchConsistency = Objects.requireNonNull(watchConsistency, "watchConsistency");
        // Refused here rather than by the first watch: a period is settled once, when the store is
        // built, and a run that parks hours later is the worst moment to learn it was never usable.
        if (watchPollPeriod != null
                && watchPollPeriod.compareTo(DisCasClient.MIN_WATCH_POLL_PERIOD) < 0) {
            throw new IllegalArgumentException(
                    "watchPollPeriod must be at least "
                            + DisCasClient.MIN_WATCH_POLL_PERIOD.toMillis() + "ms, but got "
                            + watchPollPeriod.toMillis() + "ms; only the client's own configuration "
                            + "may go below it");
        }
        this.watchPollPeriod = watchPollPeriod;
        this.ownsClient = ownsClient;
    }

    @Override
    public CompletionStage<Entry> get(final String key) {
        return client.get(key).thenApply(DiscasCoordinationStore::entryOf);
    }

    @Override
    public CompletionStage<Boolean> compareAndSet(final String key,
                                                  final String expectedVersion,
                                                  final String value) {
        return client.cas(key, versionOf(expectedVersion), value).thenApply(result -> result.swapped());
    }

    @Override
    public CompletionStage<Entry> awaitChange(final String key,
                                              final String sinceVersion,
                                              final Duration maxWait) {
        final Version since = versionOf(sinceVersion);
        final CompletionStage<WatchResult> watched = watchPollPeriod == null
                ? client.watch(key, since, maxWait, watchConsistency)
                : client.watch(key, since, maxWait, watchConsistency, watchPollPeriod);
        return watched.thenApply(DiscasCoordinationStore::entryOf);
    }

    @Override
    public CompletionStage<LeaseAttempt> tryAcquire(final String key,
                                                    final String ownerId,
                                                    final Duration ttl) {
        return attempt(key, ownerId, ttl, true);
    }

    @Override
    public CompletionStage<Boolean> renew(final String key, final LeaseHandle handle, final Duration ttl) {
        return renew(key, DiscasLeaseHandle.tokenOf(handle), ttl, true);
    }

    private CompletionStage<Boolean> renew(final String key,
                                           final LockToken token,
                                           final Duration ttl,
                                           final boolean lastChance) {
        // A renew is a read and then a write fenced on what the read saw, so it can lose to anything
        // that touched the key in between -- including a linearizable read by somebody else, which
        // re-accepts the current value at a new ballot and advances the version although nothing was
        // written. discas calls that CONTENDED and says of it that nothing was written and nothing is
        // known to be lost. It is emphatically not false: false here tells a live run to stop, and
        // stopping a run because a bystander read the key would be the worst answer available.
        //
        // So it is asked again, once, which is what re-reading and deciding again amounts to -- the
        // second call reads afresh and comes back with a definite APPLIED, EXPIRED or HELD_BY_OTHER.
        // Once and not in a loop: a renew that keeps losing has a busy key, and when to look again is
        // the keep-alive timer's question.
        return client.renewLock(key, token, ttl).thenCompose(result -> {
            if (lastChance && result.status() == LockWriteStatus.CONTENDED) {
                return renew(key, token, ttl, false);
            }
            return completed(result.applied());
        });
    }

    @Override
    public CompletionStage<Void> release(final String key, final LeaseHandle handle) {
        return release(key, DiscasLeaseHandle.tokenOf(handle), true);
    }

    private CompletionStage<Void> release(final String key,
                                          final LockToken token,
                                          final boolean lastChance) {
        // CONTENDED matters here for the same reason as in renew and with a cost of its own: a
        // release that did not land leaves the lease standing until it lapses, and the next run
        // waits out a lease nobody is holding. Asked again, once. Every other answer is done --
        // releasing a lease already lapsed or already released is not an error, and one taken over
        // is no longer this caller's to end.
        return client.release(key, token).thenCompose(result -> {
            if (lastChance && result.status() == LockWriteStatus.CONTENDED) {
                return release(key, token, false);
            }
            return completed(null);
        });
    }

    @Override
    public void close() {
        if (ownsClient) {
            client.close();
        }
    }

    // lastChance is the whole retry policy: an answer saying the key is free -- an acquire that lost
    // its fenced write to somebody who left no lease, or a recovery finding the lease it was sent for
    // already lapsed -- earns exactly one more attempt, and whatever that returns is final. Without it
    // a caller with a zero handover budget, which is the default, would give up on a key nobody holds.
    // With more than one it would be a wait loop, which is handoverWait's job and not this method's.
    private CompletionStage<LeaseAttempt> attempt(final String key,
                                                  final String ownerId,
                                                  final Duration ttl,
                                                  final boolean lastChance) {
        return client.tryLock(key, ttl, ownerId)
                .thenCompose(result -> acquireOf(key, ownerId, ttl, lastChance, result));
    }

    private CompletionStage<LeaseAttempt> acquireOf(final String key,
                                                    final String ownerId,
                                                    final Duration ttl,
                                                    final boolean lastChance,
                                                    final LockAcquireResult result) {
        switch (result.status()) {
            case ACQUIRED:
                return completed(acquired(result.lock()));
            case HELD_BY_SELF:
                // A live lease already standing in this owner's name: the acquire whose outcome was
                // never learnt did land. discas hands back no Lock here, because two callers sharing
                // an owner id would each be given the same lease, and asks the caller to name
                // recoverLock instead -- which is the caller stating the id is its own. It is: the
                // lease is taken under ownerId/runId, and a run holds one lease.
                return client.recoverLock(key, ownerId)
                        .thenCompose(recovered -> recoveredOf(key, ownerId, ttl, lastChance, recovered));
            case HELD_BY_OTHER:
                return contended(key, result.observed());
            case NOT_HELD:
                return free(key, ownerId, ttl, lastChance);
            case NOT_LOCK_RECORD:
                throw notALockRecord(key);
            default:
                throw unexpected(key, result);
        }
    }

    private CompletionStage<LeaseAttempt> recoveredOf(final String key,
                                                      final String ownerId,
                                                      final Duration ttl,
                                                      final boolean lastChance,
                                                      final LockAcquireResult recovered) {
        switch (recovered.status()) {
            case ACQUIRED:
                final Lock lock = recovered.lock();
                // The record's own token and generation, so this lease renews and releases as the
                // one the acquire would have handed over. Same lease, not a new one.
                return completed(new LeaseAttempt.HeldBySelf(handleOf(lock), lock.remainingLease()));
            case NOT_HELD:
                return free(key, ownerId, ttl, lastChance);
            case HELD_BY_OTHER:
                return contended(key, recovered.observed());
            case NOT_LOCK_RECORD:
                throw notALockRecord(key);
            default:
                throw unexpected(key, recovered);
        }
    }

    private CompletionStage<LeaseAttempt> free(final String key,
                                               final String ownerId,
                                               final Duration ttl,
                                               final boolean lastChance) {
        // Nobody holds the key. One more attempt if one is left, and otherwise contention with no name.
        return lastChance
                ? attempt(key, ownerId, ttl, false)
                : completed(new LeaseAttempt.HeldByOther(null, null));
    }

    private static CompletionStage<LeaseAttempt> contended(final String key, final LockInfo observed) {
        if (observed == null) {
            // HELD_BY_OTHER names a holder. A fenced write that lost to somebody who left the key free
            // is NOT_HELD and is answered as free above, so there is no way left to be told the key is
            // held and not be told by whom. An answer without a record is one this adapter does not
            // understand, and saying so is better than reading it as free and taking a lease somebody
            // may be holding.
            throw noHolderNamed(key);
        }
        return completed(heldByOther(observed));
    }

    private static LeaseAttempt acquired(final Lock lock) {
        return new LeaseAttempt.Acquired(handleOf(lock), lock.remainingLease());
    }

    private static DiscasLeaseHandle handleOf(final Lock lock) {
        return new DiscasLeaseHandle(lock.ownerId(), lock.fencingToken(), lock.token());
    }

    private static IllegalStateException notALockRecord(final String key) {
        return new IllegalStateException(
                "key '" + key + "' holds a value, not a lock record; keep leases under a "
                        + "prefix of their own");
    }

    private static IllegalStateException noHolderNamed(final String key) {
        return new IllegalStateException(
                "key '" + key + "' was reported held by another owner but no holder was named; "
                        + "this adapter needs discas 0.0.2 or later");
    }

    private static IllegalStateException unexpected(final String key, final LockAcquireResult result) {
        return new IllegalStateException(
                "unexpected lock status " + result.status() + " for key '" + key + "'");
    }

    private static <T> CompletionStage<T> completed(final T value) {
        return CompletableFuture.completedFuture(value);
    }

    private static LeaseAttempt heldByOther(final LockInfo observed) {
        // No remaining lease, and that is the honest answer rather than a missing feature. What discas
        // holds about somebody else's lease is a deadline in epoch milliseconds, and turning that into
        // "how much is left" means subtracting one wall clock from another -- a period measured with an
        // instrument that is allowed to step, and on two machines that are allowed to disagree. Periods
        // in piplex are measured monotonically or not at all, and no monotonic reading of another
        // holder's lease exists to be had: a monotonic clock has no meaning outside the process that
        // read it. So the store says it cannot tell, which is what the field is for, and a parked
        // candidate looks again on its own interval instead of sleeping until a deadline it cannot
        // trust. Only the holder can measure its own lease, and it does -- see Lock.remainingLease.
        return new LeaseAttempt.HeldByOther(observed.ownerId(), null);
    }

    private static Version versionOf(final String version) {
        // Version.parse maps anything it does not recognise, INITIAL_VERSION included, to INITIAL.
        return Version.parse(version);
    }

    private static Entry entryOf(final GetResult result) {
        return entryOf(result.exists(), result.value(), result.version());
    }

    private static Entry entryOf(final WatchResult result) {
        return entryOf(result.exists(), result.value(), result.version());
    }

    private static Entry entryOf(final boolean exists, final ByteBuffer value, final Version version) {
        if (!exists || value == null) {
            return Entry.absent(version.token());
        }
        return Entry.of(StandardCharsets.UTF_8.decode(value.duplicate()).toString(), version.token());
    }
}
