/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.discas;

import io.github.green4j.discas.client.ClusterClock;
import io.github.green4j.discas.client.DisCasClient;
import io.github.green4j.discas.client.DisCasClientFactory;
import io.github.green4j.discas.client.lock.LockInfoStatus;
import io.github.green4j.discas.client.lock.LockValueCodec;
import io.github.green4j.discas.client.transport.ClientTransport;
import io.github.green4j.discas.client.transport.InProcessClientBootstrap;
import io.github.green4j.discas.client.transport.InProcessClientTransport;
import io.github.green4j.discas.common.EventLoop;
import io.github.green4j.discas.common.client.ClientMessage;
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
import io.github.green4j.piplex.ContendedException;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.CoordinationStoreContract;
import io.github.green4j.piplex.store.Entry;
import io.github.green4j.piplex.store.LeaseAttempt;
import io.github.green4j.piplex.store.LeaseHandle;
import io.github.green4j.piplex.store.Watch;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

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
class DiscasCoordinationStoreTest extends CoordinationStoreContract {

    private static final ClusterId CLUSTER = ClusterId.of("piplex-test");
    private static final List<NodeId> NODE_IDS = List.of(NodeId.of("1"), NodeId.of("2"), NodeId.of("3"));
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final Duration SHORT_LEASE = Duration.ofSeconds(1);
    private static final Duration SHORT_WAIT = Duration.ofSeconds(1);
    private static final long OP_TIMEOUT_SECONDS = 15L;
    private static final long READY_BUDGET_MS = 60_000L;
    private static final long PROBE_TIMEOUT_MS = 2_000L;
    private static final long POLL_INTERVAL_MS = 100L;
    /** Enough turns of the race for a write to land inside a read-then-write at least once. */
    private static final int RACE_ROUNDS = 60;
    /** Enough writers on one key that a renewal loses the compare twice running rather than once. */
    private static final int BYSTANDERS = 4;

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

    @Override
    protected CoordinationStore subject() {
        return store;
    }

    @Override
    protected CoordinationStore newStore() {
        return new DiscasCoordinationStore(client, ReadConsistency.LINEARIZABLE, false);
    }

    @Override
    protected String key(final String name) {
        return "piplex-test/" + name + "-" + KEYS.incrementAndGet();
    }

    @Override
    protected Duration shortLease() {
        return SHORT_LEASE;
    }

    @Override
    protected Duration shortWait() {
        return SHORT_WAIT;
    }

    // A compare fenced on a lease key's own version overwrites the lock record: telling the two apart
    // would take a read first, and a linearizable read moves the version the write is fenced on.
    @Override
    protected boolean refusesAValueAtALeasesVersion() {
        return false;
    }

    @Override
    protected void letPass(final Duration duration) throws InterruptedException {
        Thread.sleep(duration.toMillis());
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
            assertTrue(extended, "Round " + round + ": the lease was never lost, only written past");
        }

        assertEquals(LockInfoStatus.LOCKED, done(client.getLockInfo(key)).status());
    }

    /**
     * The same race, turned up until the single retry is not enough either. Twice contended is still
     * nothing written and nothing known to be lost, so what it must never come back as is {@code
     * false}: that is this interface saying the lease is gone, and a live run reads it as an order to
     * stop. Unknown is what it is, and unknown is a failure.
     *
     * <p>Staged with writers that keep bumping the key rather than one write per round, because a
     * renew loses two compares running only while somebody is writing throughout both of them. How
     * often that happens is the cluster's to decide, so the assertion is on every answer rather than
     * on reaching the awkward one: whatever a bystander does, this lease is not lost.
     */
    @Test
    void neverSaysALeaseIsLostWhileBystandersAreOnlyWritingPastIt() throws Exception {
        final String key = leaseKey();
        final LeaseHandle handle =
                assertInstanceOf(LeaseAttempt.Acquired.class, done(store.tryAcquire(key, "owner-a/run-1", TTL)))
                        .handle();

        final AtomicBoolean bumping = new AtomicBoolean(true);
        final List<Thread> bystanders = new ArrayList<>();
        for (int i = 0; i < BYSTANDERS; i++) {
            final Thread bystander = new Thread(() -> {
                while (bumping.get()) {
                    bumpVersionOf(key).join();
                }
            }, "bystander-" + i);
            bystander.setDaemon(true);
            bystander.start();
            bystanders.add(bystander);
        }
        try {
            for (int round = 0; round < RACE_ROUNDS; round++) {
                try {
                    assertTrue(store.renew(key, handle, TTL).toCompletableFuture()
                                    .get(OP_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                            "Round " + round + ": the lease was never lost, only written past");
                } catch (final ExecutionException contended) {
                    assertInstanceOf(ContendedException.class, contended.getCause(),
                            "Round " + round + ": a renewal fails only for not knowing");
                }
            }
        } finally {
            bumping.set(false);
            for (final Thread bystander : bystanders) {
                bystander.join(TimeUnit.SECONDS.toMillis(OP_TIMEOUT_SECONDS));
            }
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
                    "Round " + round + ": the release did not land and the lease is still standing");
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
    void refusesALeaseAnotherOwnerOrRunHolds() {
        final String key = leaseKey();
        done(store.tryAcquire(key, "owner-a/run-1", TTL));

        for (final String other : new String[] {"owner-b/run-1", "owner-a/run-2"}) {
            final LeaseAttempt.HeldByOther held =
                    assertInstanceOf(LeaseAttempt.HeldByOther.class, done(store.tryAcquire(key, other, TTL)));

            assertEquals("owner-a/run-1", held.ownerId(), other);
            // Another holder's deadline is on another machine's wall clock, so the store does not say.
            assertNull(held.remaining(), other);
        }
    }

    @Test
    void pollsAtTheLongPeriodOnlyForABackgroundWatch() throws Exception {
        // A client of its own, whose transport counts the answers to reads: every poll is one.
        final EventLoop loop = new EventLoop("piplex-test-polling");
        final ClientId id = ClientId.of("piplex-test-polling");
        final CountingReads reads = new CountingReads(new InProcessClientTransport(loop, NODE_IDS, id));
        final CoordinationStore polling = new DiscasCoordinationStore(new DisCasClient(id, reads, loop, true),
                ReadConsistency.LINEARIZABLE, Duration.ofSeconds(20L), true);
        try {
            final String key = valueKey();
            assertTrue(done(polling.compareAndSet(key, CoordinationStore.INITIAL_VERSION, "one")));
            final Entry before = done(polling.get(key));
            reads.answered.set(0);
            final CompletableFuture<Entry> background = polling.awaitChange(
                    key, before.version(), Duration.ofSeconds(15L), Watch.BACKGROUND).toCompletableFuture();
            final CompletionStage<Entry> urgent =
                    polling.awaitChange(key, before.version(), Duration.ofSeconds(15L), Watch.URGENT);
            // Past both first polls, so the write is what a later poll has to find. A read answered
            // before the write was sent cannot have seen it.
            awaitUntil("both watches polled once", () -> reads.answered.get() >= 2);
            assertTrue(done(polling.compareAndSet(key, before.version(), "two")));

            // The client's own period is a second, spread up to five.
            assertEquals("two", urgent.toCompletableFuture().get(10L, TimeUnit.SECONDS).value());
            assertFalse(background.isDone(), "A background watch waits out its own period");
        } finally {
            polling.close();
        }
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
            throw new AssertionError("Round " + round + ": nobody held '" + key
                    + "' and the acquire still came back " + attempt);
        }
        assertEquals(owner, handle.ownerId(), "Round " + round + ": the lease is in the wrong name");
    }

    @Test
    void givesBackALeaseGrantedAfterItWasClosed() throws Exception {
        // The token that renews or releases a lease is kept only in the answer to the acquire. Closing
        // the store fails the caller's future, and the acquire the cluster is already working on still
        // lands: dropped, the lease would stand until its term lapsed with nothing able to end it.
        final EventLoop loop = new EventLoop("piplex-test-closing");
        final ClientId id = ClientId.of("piplex-test-closing");
        final HeldBack held = new HeldBack(new InProcessClientTransport(loop, NODE_IDS, id));
        final DisCasClient own = new DisCasClient(id, held, loop, true);
        final CoordinationStore closing = new DiscasCoordinationStore(own, ReadConsistency.LINEARIZABLE, false);
        final String key = leaseKey();
        try {
            final CompletableFuture<LeaseAttempt> taking =
                    closing.tryAcquire(key, "euc1-blue", TTL).toCompletableFuture();
            awaitUntil("the acquire reached the cluster", () -> held.sent.get() > 0);

            closing.close();
            assertThrows(ExecutionException.class, () -> taking.get(10L, TimeUnit.SECONDS),
                    "A closed store answers nobody");

            held.letGo();

            // The lease is the assertion: somebody else can take the key, which is only true if the one
            // granted to a caller that had gone was given back.
            awaitUntil("the orphaned lease went back", () -> {
                final LeaseAttempt next = done(newStore().tryAcquire(key, "euc1-green", TTL));
                return next instanceof LeaseAttempt.Acquired;
            });
        } finally {
            own.close();
        }
    }

    private static String valueKey() {
        return "piplex-test/value-" + KEYS.incrementAndGet();
    }

    private static String leaseKey() {
        return "piplex-test/lease-" + KEYS.incrementAndGet();
    }

    /**
     * A transport which holds everything the cluster answers until it is let go of.
     */
    private static final class HeldBack implements ClientTransport {

        private final ClientTransport delegate;
        private final AtomicInteger sent = new AtomicInteger();
        private final List<ClientMessage> waiting = new CopyOnWriteArrayList<>();
        private volatile Consumer<ClientMessage> handler;
        private volatile boolean holding = true;

        private HeldBack(final ClientTransport delegate) {
            this.delegate = delegate;
        }

        void letGo() {
            holding = false;
            final Consumer<ClientMessage> to = handler;
            waiting.forEach(to);
            waiting.clear();
        }

        @Override
        public void send(final NodeId targetNodeId, final ClientMessage message) {
            sent.incrementAndGet();
            delegate.send(targetNodeId, message);
        }

        @Override
        public void register(final Consumer<ClientMessage> given) {
            handler = given;
            delegate.register(message -> {
                if (holding) {
                    waiting.add(message);
                    return;
                }
                given.accept(message);
            });
        }

        @Override
        public void registerConnectionLost(final Consumer<NodeId> handler) {
            delegate.registerConnectionLost(handler);
        }

        @Override
        public List<NodeId> peers() {
            return delegate.peers();
        }

        @Override
        public int clusterSize() {
            return delegate.clusterSize();
        }

        @Override
        public void bindClock(final ClusterClock clock) {
            delegate.bindClock(clock);
        }
    }

    /**
     * A transport which counts the answers to reads on their way to the client.
     */
    private static final class CountingReads implements ClientTransport {

        private final ClientTransport delegate;
        private final AtomicInteger answered = new AtomicInteger();

        private CountingReads(final ClientTransport delegate) {
            this.delegate = delegate;
        }

        @Override
        public void send(final NodeId targetNodeId, final ClientMessage message) {
            delegate.send(targetNodeId, message);
        }

        @Override
        public void register(final Consumer<ClientMessage> handler) {
            delegate.register(message -> {
                if (message instanceof ClientMessage.ClientGetResp) {
                    answered.incrementAndGet();
                }
                handler.accept(message);
            });
        }

        @Override
        public void registerConnectionLost(final Consumer<NodeId> handler) {
            delegate.registerConnectionLost(handler);
        }

        @Override
        public List<NodeId> peers() {
            return delegate.peers();
        }

        @Override
        public int clusterSize() {
            return delegate.clusterSize();
        }

        @Override
        public void bindClock(final ClusterClock clock) {
            delegate.bindClock(clock);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static void awaitUntil(final String what, final BooleanSupplier condition) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(OP_TIMEOUT_SECONDS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0L) {
                throw new AssertionError("Gave up waiting until " + what);
            }
            Thread.sleep(POLL_INTERVAL_MS);
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
