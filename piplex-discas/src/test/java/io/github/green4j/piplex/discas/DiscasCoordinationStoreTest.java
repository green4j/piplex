/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.discas;

import io.github.green4j.discas.client.DisCasClient;
import io.github.green4j.discas.client.DisCasClientFactory;
import io.github.green4j.discas.client.lock.LockInfoStatus;
import io.github.green4j.discas.client.lock.LockValueCodec;
import io.github.green4j.discas.client.transport.InProcessClientBootstrap;
import io.github.green4j.discas.common.client.ReadConsistency;
import io.github.green4j.discas.common.identity.ClientId;
import io.github.green4j.discas.common.identity.ClusterId;
import io.github.green4j.discas.common.identity.NodeId;
import io.github.green4j.discas.node.DisCasNode;
import io.github.green4j.discas.node.DisCasNodeFactory;
import io.github.green4j.discas.node.NodeConfig;
import io.github.green4j.discas.node.membership.InMemoryMembers;
import io.github.green4j.discas.node.transport.InProcessPeerBootstrap;
import io.github.green4j.discas.node.wal.FileWal;
import io.github.green4j.discas.node.wal.StorageConfig;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.Entry;
import io.github.green4j.piplex.store.LeaseAttempt;
import io.github.green4j.piplex.store.LeaseHandle;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The adapter against a real cluster, brought up inside the test JVM.
 *
 * <p>{@code DisCasClient} is final, so there is no stand-in for it to be driven against, and the
 * statuses that matter here -- {@code HELD_BY_SELF} above all -- are decided inside the client from
 * what a linearizable read finds in the key. A fake would be asserting on the mapping of statuses
 * this test is trying to prove the client produces.
 */
class DiscasCoordinationStoreTest {

    private static final ClusterId CLUSTER = ClusterId.of("piplex-test");
    private static final List<NodeId> NODE_IDS = List.of(NodeId.of("1"), NodeId.of("2"), NodeId.of("3"));
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final long OP_TIMEOUT_SECONDS = 15L;
    private static final long READY_BUDGET_MS = 60_000L;
    private static final long PROBE_TIMEOUT_MS = 2_000L;
    private static final long POLL_INTERVAL_MS = 100L;
    /** Enough turns of the race for a write to land inside a read-then-write at least once. */
    private static final int RACE_ROUNDS = 60;

    private static final List<DisCasNode> NODES = new ArrayList<>();
    private static final AtomicInteger KEYS = new AtomicInteger();
    /** Kept moving so a hand-written lapsed record never looks like the one already on the key. */
    private static final AtomicInteger LAPSED_GENERATIONS = new AtomicInteger();

    private static DisCasClient client;
    private static CoordinationStore store;

    @BeforeAll
    static void startCluster(@TempDir final Path baseDir) throws Exception {
        for (final NodeId nodeId : NODE_IDS) {
            final Path walDir = baseDir.resolve("node-" + nodeId);
            Files.createDirectories(walDir);
            final FileWal wal = new FileWal(StorageConfig.builder().baseDirectory(walDir).build());
            wal.initialize();
            NODES.add(DisCasNodeFactory.create(
                    new NodeConfig(nodeId, CLUSTER, NODE_IDS.size()),
                    new InProcessPeerBootstrap(InMemoryMembers.ofNodes(NODE_IDS)),
                    wal));
        }
        client = DisCasClientFactory.create(ClientId.of("piplex-test"), new InProcessClientBootstrap(NODE_IDS));
        for (final DisCasNode node : NODES) {
            node.start();
        }
        awaitReady();
        store = new DiscasCoordinationStore(client);
    }

    @AfterAll
    static void stopCluster() {
        if (store != null) {
            store.close();
        }
        if (client != null) {
            client.close();
        }
        for (final DisCasNode node : NODES) {
            node.close();
        }
    }

    @Test
    void grantsALeaseAndTakesItBack() {
        final String key = leaseKey();

        final LeaseAttempt.Acquired taken =
                assertInstanceOf(LeaseAttempt.Acquired.class, done(store.tryAcquire(key, "owner-a/run-1", TTL)));
        assertEquals("owner-a/run-1", taken.handle().ownerId());
        assertTrue(taken.remaining().compareTo(Duration.ZERO) > 0);

        assertTrue(done(store.renew(key, taken.handle(), TTL)));
        done(store.release(key, taken.handle()));

        assertInstanceOf(LeaseAttempt.Acquired.class, done(store.tryAcquire(key, "owner-b/run-1", TTL)));
    }

    @Test
    void tellsTheHolderItAlreadyHoldsRatherThanRefusingIt() {
        final String key = leaseKey();
        final String owner = "owner-a/run-1";

        final LeaseAttempt.Acquired first =
                assertInstanceOf(LeaseAttempt.Acquired.class, done(store.tryAcquire(key, owner, TTL)));

        // What an acquire whose outcome was never learned looks like on retry. discas answers
        // HELD_BY_SELF and hands back no lock; the adapter names recoverLock to get one.
        final LeaseAttempt.HeldBySelf mine =
                assertInstanceOf(LeaseAttempt.HeldBySelf.class, done(store.tryAcquire(key, owner, TTL)));

        assertEquals(owner, mine.handle().ownerId());
        assertEquals(first.handle().fencingToken(), mine.handle().fencingToken());
        assertTrue(mine.remaining().compareTo(Duration.ZERO) > 0);
    }

    @Test
    void recoversALeaseThatRenewsAndReleasesAsTheOriginalWould() {
        final String key = leaseKey();
        final String owner = "owner-a/run-1";
        done(store.tryAcquire(key, owner, TTL));

        final LeaseHandle recovered =
                assertInstanceOf(LeaseAttempt.HeldBySelf.class, done(store.tryAcquire(key, owner, TTL)))
                        .handle();

        assertTrue(done(store.renew(key, recovered, TTL)));
        done(store.release(key, recovered));
        assertInstanceOf(LeaseAttempt.Acquired.class, done(store.tryAcquire(key, "owner-b/run-1", TTL)));
    }

    /**
     * A renew is a read and a write fenced on what the read saw, so a write that lands in between
     * loses it the compare -- discas answers CONTENDED, which says nothing was written and nothing
     * is known to be lost. Reported as {@code false} it would tell a live run to stop, and the run
     * would stop for a bystander's write.
     *
     * <p>Staged with a write that changes nothing that matters: the same owner, the same token, the
     * same generation, one millisecond more lease. It advances the version, so a renew that reads
     * before it and writes after it loses the compare, and the tenancy it loses to is still its own.
     * One such write per renew, so the single retry is always enough to settle it.
     */
    @Test
    void keepsTheLeaseWhenAWriteRacesTheRenewal() {
        final String key = leaseKey();
        final LeaseHandle handle =
                assertInstanceOf(LeaseAttempt.Acquired.class, done(store.tryAcquire(key, "owner-a/run-1", TTL)))
                        .handle();

        for (int round = 0; round < RACE_ROUNDS; round++) {
            final CompletableFuture<?> bump = bumpVersionOf(key);
            final boolean extended = done(store.renew(key, handle, TTL));
            bump.join();
            assertTrue(extended, "round " + round + ": the lease was never lost, only written past");
        }

        assertEquals(LockInfoStatus.LOCKED, done(client.getLockInfo(key)).status());
    }

    /**
     * The same race on the way out, where losing it costs the next run rather than this one: a
     * release that did not land leaves the lease standing until it lapses.
     */
    @Test
    void releasesTheLeaseWhenAWriteRacesIt() {
        for (int round = 0; round < RACE_ROUNDS; round++) {
            final String key = leaseKey();
            final LeaseHandle handle =
                    assertInstanceOf(LeaseAttempt.Acquired.class,
                            done(store.tryAcquire(key, "owner-a/run-1", TTL))).handle();

            final CompletableFuture<?> bump = bumpVersionOf(key);
            done(store.release(key, handle));
            bump.join();

            assertNotEquals(LockInfoStatus.LOCKED, done(client.getLockInfo(key)).status(),
                    "round " + round + ": the release did not land and the lease is still standing");
        }
    }

    private static CompletableFuture<?> bumpVersionOf(final String key) {
        // One write to the lease key that leaves the tenancy exactly as it was -- only the version
        // moves. A lost compare here means the renew or release beat it to the key, which is simply
        // a round with no contention in it.
        return client.get(key).thenCompose(current -> {
            final LockValueCodec.LockRecord held = LockValueCodec.decode(current.value());
            final ByteBuffer same = LockValueCodec.encode(new LockValueCodec.LockRecord(
                    held.ownerId(),
                    held.token(),
                    held.acquiredAtEpochMs(),
                    held.leaseUntilEpochMs() + 1L,
                    held.generation()));
            return client.cas(key, current.version(), same);
        });
    }

    @Test
    void refusesALeaseAnotherOwnerHolds() {
        final String key = leaseKey();
        done(store.tryAcquire(key, "owner-a/run-1", TTL));

        final LeaseAttempt.HeldByOther other =
                assertInstanceOf(LeaseAttempt.HeldByOther.class, done(store.tryAcquire(key, "owner-b/run-1", TTL)));

        assertEquals("owner-a/run-1", other.ownerId());
        // Another holder's deadline is on another machine's wall clock, so the store does not say.
        assertNull(other.remaining());
    }

    @Test
    void separatesRunsOfTheSameDeployment() {
        final String key = leaseKey();
        done(store.tryAcquire(key, "owner-a/run-1", TTL));

        final LeaseAttempt.HeldByOther other =
                assertInstanceOf(LeaseAttempt.HeldByOther.class, done(store.tryAcquire(key, "owner-a/run-2", TTL)));

        assertEquals("owner-a/run-1", other.ownerId());
    }

    @Test
    void readsAndWritesAKey() {
        final String key = valueKey();

        final Entry absent = done(store.get(key));
        assertFalse(absent.exists());
        assertNull(absent.value());

        assertTrue(done(store.compareAndSet(key, CoordinationStore.INITIAL_VERSION, "one")));

        final Entry written = done(store.get(key));
        assertTrue(written.exists());
        assertEquals("one", written.value());
        assertNotEquals(CoordinationStore.INITIAL_VERSION, written.version());

        assertFalse(done(store.compareAndSet(key, CoordinationStore.INITIAL_VERSION, "two")));
        assertTrue(done(store.compareAndSet(key, written.version(), "two")));
        assertEquals("two", done(store.get(key)).value());
    }

    @Test
    void answersAtOnceWhenTheVersionHasAlreadyMoved() {
        final String key = valueKey();
        assertTrue(done(store.compareAndSet(key, CoordinationStore.INITIAL_VERSION, "one")));
        final Entry first = done(store.get(key));
        assertTrue(done(store.compareAndSet(key, first.version(), "two")));

        // The version asked from is already behind, so there is nothing to wait for. This is the case a
        // waiter hits after a restart, and it is why waiting can be resumed from a version alone.
        final Entry changed = done(store.awaitChange(key, first.version(), Duration.ofSeconds(10L)));
        assertTrue(changed.exists());
        assertEquals("two", changed.value());
        assertNotEquals(first.version(), changed.version());
    }

    @Test
    void comesBackWithWhatIsInForceWhenTheWaitRunsOut() {
        final String key = valueKey();
        assertTrue(done(store.compareAndSet(key, CoordinationStore.INITIAL_VERSION, "one")));
        final Entry written = done(store.get(key));

        // Nothing asserted about the version: a linearizable poll re-accepts the value at a new ballot
        // and may advance it although nobody wrote. That is exactly why callers here compare state and
        // never count wakeups, and a test which demanded the version stand still would be demanding a
        // guarantee piplex deliberately does not rely on.
        final Entry after = done(store.awaitChange(key, written.version(), Duration.ofSeconds(2L)));
        assertTrue(after.exists());
        assertEquals("one", after.value());
    }

    @Test
    void noticesAWriteMadeWhileItIsWaiting() {
        final CoordinationStore polling = new DiscasCoordinationStore(
                client, ReadConsistency.LINEARIZABLE, DisCasClient.MIN_WATCH_POLL_PERIOD, false);
        final String key = valueKey();
        assertTrue(done(polling.compareAndSet(key, CoordinationStore.INITIAL_VERSION, "one")));
        final Entry before = done(polling.get(key));

        final CompletionStage<Entry> waiting =
                polling.awaitChange(key, before.version(), Duration.ofSeconds(30L));
        assertTrue(done(polling.compareAndSet(key, before.version(), "two")));

        final Entry seen = done(waiting);
        assertTrue(seen.exists());
        assertEquals("two", seen.value());
    }

    @Test
    void refusesAPollPeriodOnlyTheClientMayGoBelow() {
        // Refused when the store is built rather than when a watch first runs: the period is settled
        // once, and a run parking hours later is the worst moment to learn it was never usable.
        final Duration tooShort = DisCasClient.MIN_WATCH_POLL_PERIOD.minusMillis(1L);
        assertThrows(IllegalArgumentException.class, () -> new DiscasCoordinationStore(
                client, ReadConsistency.LINEARIZABLE, tooShort, false));
    }

    /**
     * An acquire whose fenced write loses to somebody who left the key free. discas answers NOT_HELD,
     * the adapter spends its one retry on it, and what it must never answer is HELD_BY_OTHER -- a
     * caller told that parks and waits out a handover budget for a lease nobody holds. Under 0.0.1
     * this case arrived as exactly that, which is why the branch reading it was taken out.
     *
     * <p>Same staging and same caveat as the renewal race above: a write that never lands inside the
     * read-then-write window leaves the round proving nothing, so it is played enough times.
     */
    @Test
    void takesTheKeyWhenTheWriteItLostWentToSomebodyWhoLeftItFree() {
        final String key = leaseKey();
        final String owner = "owner-a/run-1";

        for (int round = 0; round < RACE_ROUNDS; round++) {
            layALapsedLockOn(key).join();
            final CompletableFuture<?> lost = layALapsedLockOn(key);
            final LeaseAttempt attempt = done(store.tryAcquire(key, owner, TTL));
            lost.join();

            heldAfter(attempt, key, owner, round);
        }
    }

    /**
     * The other road to the same answer: told the lease already stands in its own name, the acquire
     * goes to recoverLock for it and finds it lapsed. Nobody holds it, so a new one is there to take.
     */
    @Test
    void takesTheKeyWhenTheLeaseItWentToRecoverHadAlreadyLapsed() {
        final String key = leaseKey();
        final String owner = "owner-a/run-1";

        for (int round = 0; round < RACE_ROUNDS; round++) {
            layALapsedLockOn(key).join();
            // A live lease in this owner's own name is what makes the acquire below be told
            // HELD_BY_SELF and go to recoverLock.
            assertInstanceOf(LeaseAttempt.Acquired.class, done(store.tryAcquire(key, owner, TTL)));

            final CompletableFuture<?> lapsed = layALapsedLockOn(key);
            final LeaseAttempt attempt = done(store.tryAcquire(key, owner, TTL));
            lapsed.join();

            heldAfter(attempt, key, owner, round);
        }
    }

    // A lock record that expired long ago: the key holds a lock, and nobody holds the key.
    private static CompletableFuture<?> layALapsedLockOn(final String key) {
        return client.get(key).thenCompose(current -> {
            final LockValueCodec.LockRecord lapsed = new LockValueCodec.LockRecord(
                    "owner-who-left/run-1",
                    ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}),
                    1L,
                    2L,
                    LAPSED_GENERATIONS.incrementAndGet());
            return client.cas(key, current.version(), LockValueCodec.encode(lapsed));
        });
    }

    // The lease the round has to end holding. Acquired or HeldBySelf, either is correct; HeldByOther
    // is the answer both tests above exist to rule out. Checked by the name on it rather than by
    // renewing it: the write staging the race lands on whatever lease was just taken and ends it.
    private static void heldAfter(final LeaseAttempt attempt,
                                  final String key,
                                  final String owner,
                                  final int round) {
        final LeaseHandle handle;
        if (attempt instanceof LeaseAttempt.Acquired taken) {
            handle = taken.handle();
        } else if (attempt instanceof LeaseAttempt.HeldBySelf mine) {
            handle = mine.handle();
        } else {
            throw new AssertionError("round " + round + ": nobody held '" + key
                    + "' and the acquire still came back " + attempt);
        }
        assertEquals(owner, handle.ownerId(), "round " + round + ": the lease is in the wrong name");
    }

    private static String valueKey() {
        return "piplex-test/value-" + KEYS.incrementAndGet();
    }

    private static String leaseKey() {
        return "piplex-test/lease-" + KEYS.incrementAndGet();
    }

    private static <T> T done(final CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(OP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the store", interrupted);
        } catch (final ExecutionException | TimeoutException failed) {
            throw new IllegalStateException("The store did not answer", failed);
        }
    }

    /**
     * A started node is not yet a formed peer mesh, so the first operation could reach a coordinator
     * that cannot see a majority and be refused for a reason no test here is about. Ask the cluster
     * rather than sleep a guessed interval.
     */
    private static void awaitReady() throws Exception {
        final long deadline = System.nanoTime() + READY_BUDGET_MS * 1_000_000L;
        Exception last = null;
        while (System.nanoTime() - deadline < 0) {
            try {
                client.get("__piplex_ready__").get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                return;
            } catch (final Exception notYet) {
                last = notYet;
                Thread.sleep(POLL_INTERVAL_MS);
            }
        }
        throw new IllegalStateException(
                "Cluster did not answer a read within " + READY_BUDGET_MS + "ms", last);
    }
}
