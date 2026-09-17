/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.store;

import io.github.green4j.piplex.TimeSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What every {@link CoordinationStore} owes the primitives, asked the same way of each of them.
 *
 * <p>An implementation's own test extends this and says how to get a store, how to make a key nobody
 * else uses, and how to let time pass. Everything here goes through {@link FailFastStore}, the way the
 * primitives see a store, so a call that throws and a stage that fails are one answer.
 */
public abstract class CoordinationStoreContract {

    private static final long TIMEOUT_SECONDS = 30L;
    private static final int MAX_ASKS = 1_000;

    // Real time for the backstop only; the store under test gets its own, and letPass moves that one.
    private static final TimeSource TIME = TimeSource.of(timers());

    /**
     * @return the store under test, open
     */
    protected abstract CoordinationStore subject();

    /**
     * @return a store of the same kind, opened for this call alone, which the test closes
     */
    protected abstract CoordinationStore newStore();

    /**
     * @param name what the key is for
     * @return a key no other test touches
     */
    protected abstract String key(String name);

    /**
     * @return a lease short enough to wait out
     */
    protected abstract Duration shortLease();

    /**
     * @return a wait short enough to sit through
     */
    protected abstract Duration shortWait();

    /**
     * Lets the given time pass, as far as the store can tell.
     *
     * <p>A store on the real clock can only wait it out. Nothing here depends on that wait being long
     * enough: what follows it waits for the state it needs, with a timeout.
     *
     * @param duration how much
     * @throws InterruptedException if waiting was interrupted
     */
    protected abstract void letPass(Duration duration) throws InterruptedException;

    /**
     * Whether a write fenced on a lease key's own version is refused. A store which cannot tell a lease
     * record from a value without a read of its own says so here, and the primitives' separate key
     * prefixes are then all that keeps the two apart.
     *
     * @return {@code true} unless the store documents otherwise
     */
    protected boolean refusesAValueAtALeasesVersion() {
        return true;
    }

    /**
     * Asks until there is an answer, letting a little time pass between asks.
     *
     * @param what  what is being waited for, for the failure
     * @param probe the answer, or {@code null} while there is none
     * @param <T>   the answer
     * @return the first answer
     * @throws InterruptedException if waiting was interrupted
     */
    private <T> T eventually(final String what, final Supplier<T> probe) throws InterruptedException {
        final Duration step = shortLease().dividedBy(10);
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        // Bounded in asks too: a store on a clock only letPass moves never lets the deadline come.
        for (int asked = 0; asked < MAX_ASKS && System.nanoTime() - deadline < 0L; asked++) {
            final T answer = probe.get();
            if (answer != null) {
                return answer;
            }
            letPass(step);
        }
        throw new AssertionError("Gave up waiting until " + what);
    }

    private static ScheduledThreadPoolExecutor timers() {
        final ScheduledThreadPoolExecutor timers = new ScheduledThreadPoolExecutor(1, task -> {
            final Thread thread = new Thread(task, "store-contract-timer");
            thread.setDaemon(true);
            return thread;
        });
        timers.setRemoveOnCancelPolicy(true);
        return timers;
    }

    private CoordinationStore store() {
        return FailFastStore.of(subject(), TIME);
    }

    @Test
    void declaresAPositiveResponseBound() {
        final Duration bound = subject().responseBound();
        assertNotNull(bound);
        assertTrue(bound.compareTo(Duration.ZERO) > 0, "A stage must be bounded by some positive time");
    }

    // ---- values ---------------------------------------------------------------------------------

    @Test
    void readsAnAbsentKeyAsAbsentWithAVersion() {
        final Entry entry = done(store().get(key("absent")));
        assertFalse(entry.exists());
        assertNotNull(entry.version(), "An absent key still has a version to wait past");
    }

    @Test
    void createsAKeyOnlyOnce() {
        final String key = key("create");
        assertTrue(done(store().compareAndSet(key, CoordinationStore.INITIAL_VERSION, "first")));
        assertFalse(done(store().compareAndSet(key, CoordinationStore.INITIAL_VERSION, "second")));
        assertEquals("first", done(store().get(key)).value());
    }

    @Test
    void refusesAStaleVersion() {
        final String key = key("stale");
        assertTrue(done(store().compareAndSet(key, CoordinationStore.INITIAL_VERSION, "first")));
        final Entry seen = done(store().get(key));
        assertTrue(done(store().compareAndSet(key, seen.version(), "second")));
        assertFalse(done(store().compareAndSet(key, seen.version(), "third")));

        final Entry now = done(store().get(key));
        assertEquals("second", now.value());
        assertNotEquals(seen.version(), now.version(), "A write that changed the value must move the version");
    }

    // ---- waiting --------------------------------------------------------------------------------

    @Test
    void answersAtOnceWhenTheKeyHasAlreadyMoved() {
        final String key = key("moved");
        assertTrue(done(store().compareAndSet(key, CoordinationStore.INITIAL_VERSION, "v")));

        final Entry entry = done(store().awaitChange(key, CoordinationStore.INITIAL_VERSION, Duration.ofHours(1)));
        assertEquals("v", entry.value());
    }

    @Test
    void comesBackUnchangedWhenTheWaitRunsOut() throws Exception {
        final String key = key("unchanged");
        assertTrue(done(store().compareAndSet(key, CoordinationStore.INITIAL_VERSION, "v")));
        final Entry seen = done(store().get(key));

        final CompletableFuture<Entry> waited =
                store().awaitChange(key, seen.version(), shortWait()).toCompletableFuture();
        letPass(shortWait());

        // The value only: a store is free to move the version without a write, which is why nobody
        // above it counts versions.
        final Entry entry = done(waited);
        assertTrue(entry.exists());
        assertEquals("v", entry.value());
        // A store that answered at the end of the wait answers for that moment. Reported as unconfirmed,
        // a guard would re-read after every watch for ever.
        assertTrue(entry.confirmed(), "A watch of a healthy store answers for the moment it ended");
    }

    @Test
    void reportsTheStateAfterWritesMadeWhileWaiting() {
        final String key = key("coalesced");
        assertTrue(done(store().compareAndSet(key, CoordinationStore.INITIAL_VERSION, "one")));
        final Entry seen = done(store().get(key));
        final CompletableFuture<Entry> waited =
                store().awaitChange(key, seen.version(), Duration.ofSeconds(TIMEOUT_SECONDS)).toCompletableFuture();

        assertTrue(done(store().compareAndSet(key, seen.version(), "two")));
        assertTrue(done(store().compareAndSet(key, done(store().get(key)).version(), "three")));

        // Coalesced: which of the two is seen is the store's business, that the key moved is not.
        final Entry entry = done(waited);
        assertNotEquals(seen.version(), entry.version());
        assertTrue(Set.of("two", "three").contains(entry.value()), "Saw " + entry.value());
    }

    // ---- leases ---------------------------------------------------------------------------------

    @Test
    void grantsALeaseToOneOwnerAtATime() {
        final String key = key("lease");
        final LeaseAttempt.Acquired taken = assertInstanceOf(LeaseAttempt.Acquired.class,
                done(store().tryAcquire(key, "owner-a/run-1", Duration.ofMinutes(1))));
        assertEquals("owner-a/run-1", taken.handle().ownerId());
        assertTrue(taken.remaining().compareTo(Duration.ZERO) > 0, "A lease just taken has time left");

        final LeaseAttempt.HeldByOther refused = assertInstanceOf(LeaseAttempt.HeldByOther.class,
                done(store().tryAcquire(key, "owner-b/run-1", Duration.ofMinutes(1))));
        assertEquals("owner-a/run-1", refused.ownerId());
    }

    @Test
    void tellsTheHolderItAlreadyHoldsTheLease() {
        final String key = key("self");
        final LeaseAttempt.Acquired taken = assertInstanceOf(LeaseAttempt.Acquired.class,
                done(store().tryAcquire(key, "owner-a/run-1", Duration.ofMinutes(1))));

        final LeaseAttempt.HeldBySelf again = assertInstanceOf(LeaseAttempt.HeldBySelf.class,
                done(store().tryAcquire(key, "owner-a/run-1", Duration.ofMinutes(1))));

        assertEquals(taken.handle().fencingToken(), again.handle().fencingToken(),
                "A recovered lease is the same lease, not a new acquisition");
        // And the recovered handle does what the original would.
        assertTrue(done(store().renew(key, again.handle(), Duration.ofMinutes(1))));
        done(store().release(key, again.handle()));
        assertInstanceOf(LeaseAttempt.Acquired.class,
                done(store().tryAcquire(key, "owner-b/run-1", Duration.ofMinutes(1))));
    }

    @Test
    void raisesTheFencingTokenOnEveryAcquisition() {
        final String key = key("fencing");
        final LeaseAttempt.Acquired first = assertInstanceOf(LeaseAttempt.Acquired.class,
                done(store().tryAcquire(key, "owner-a/run-1", Duration.ofMinutes(1))));
        done(store().release(key, first.handle()));

        final LeaseAttempt.Acquired second = assertInstanceOf(LeaseAttempt.Acquired.class,
                done(store().tryAcquire(key, "owner-b/run-1", Duration.ofMinutes(1))));
        assertTrue(second.handle().fencingToken() > first.handle().fencingToken(),
                "The token must keep climbing across a release");
    }

    @Test
    void refusesToRenewAReleasedLease() {
        final String key = key("renew-released");
        final LeaseAttempt.Acquired taken = assertInstanceOf(LeaseAttempt.Acquired.class,
                done(store().tryAcquire(key, "owner-a/run-1", Duration.ofMinutes(1))));
        done(store().release(key, taken.handle()));

        assertFalse(done(store().renew(key, taken.handle(), Duration.ofMinutes(1))));
    }

    @Test
    void releasesALeaseTwiceWithoutComplaint() {
        final String key = key("release-twice");
        final LeaseAttempt.Acquired taken = assertInstanceOf(LeaseAttempt.Acquired.class,
                done(store().tryAcquire(key, "owner-a/run-1", Duration.ofMinutes(1))));
        done(store().release(key, taken.handle()));
        done(store().release(key, taken.handle()));
    }

    @Test
    void doesNotLetAStaleHandleReleaseTheNextHolder() {
        final String key = key("stale-handle");
        final LeaseAttempt.Acquired first = assertInstanceOf(LeaseAttempt.Acquired.class,
                done(store().tryAcquire(key, "owner-a/run-1", Duration.ofMinutes(1))));
        done(store().release(key, first.handle()));
        assertInstanceOf(LeaseAttempt.Acquired.class,
                done(store().tryAcquire(key, "owner-b/run-1", Duration.ofMinutes(1))));

        done(store().release(key, first.handle()));

        assertInstanceOf(LeaseAttempt.HeldByOther.class,
                done(store().tryAcquire(key, "owner-c/run-1", Duration.ofMinutes(1))));
    }

    @Test
    void letsALeaseLapseWhenNobodyRenewsIt() throws Exception {
        final String key = key("lapse");
        final LeaseAttempt.Acquired first = assertInstanceOf(LeaseAttempt.Acquired.class,
                done(store().tryAcquire(key, "owner-a/run-1", shortLease())));

        // Asked of another owner, whose attempts cannot keep the first lease alive the way renewing
        // it would; how soon it lapses is the store's to decide.
        final LeaseAttempt.Acquired second = eventually("the lease lapsed", () -> {
            final LeaseAttempt attempt = done(store().tryAcquire(key, "owner-b/run-1", shortLease()));
            return attempt instanceof LeaseAttempt.Acquired taken ? taken : null;
        });

        assertFalse(done(store().renew(key, first.handle(), shortLease())),
                "A lapsed lease must not be renewed");
        assertTrue(second.handle().fencingToken() > first.handle().fencingToken(),
                "The token must keep climbing across a lapse");
        done(store().release(key, first.handle()));
    }

    // ---- values and leases do not mix -----------------------------------------------------------

    @Test
    void refusesALeaseOnAKeyHoldingAValue() {
        final String key = key("value-then-lease");
        assertTrue(done(store().compareAndSet(key, CoordinationStore.INITIAL_VERSION, "a value")));

        assertFails(store().tryAcquire(key, "owner-a/run-1", Duration.ofMinutes(1)));
    }

    @Test
    void refusesAWriteToAKeyUnderLease() {
        final String key = key("lease-then-value");
        final LeaseAttempt.Acquired taken = assertInstanceOf(LeaseAttempt.Acquired.class,
                done(store().tryAcquire(key, "owner-a/run-1", Duration.ofMinutes(1))));
        final String version = done(store().get(key)).version();

        // Refused either way -- a failure, or a compare that did not apply -- as long as it is not a
        // write that replaced the lease.
        final String[] versions = refusesAValueAtALeasesVersion()
                ? new String[] {CoordinationStore.INITIAL_VERSION, version}
                : new String[] {CoordinationStore.INITIAL_VERSION};
        for (final String expected : versions) {
            final CompletableFuture<Boolean> written =
                    store().compareAndSet(key, expected, "a value").toCompletableFuture();
            assertFalse(succeeded(written), "A value must not be written over a lease");
        }

        assertTrue(done(store().renew(key, taken.handle(), Duration.ofMinutes(1))),
                "The lease must survive the attempt");
        assertInstanceOf(LeaseAttempt.HeldByOther.class,
                done(store().tryAcquire(key, "owner-b/run-1", Duration.ofMinutes(1))));
    }

    // ---- closing --------------------------------------------------------------------------------

    @Test
    void refusesEverythingOnceClosed() {
        final CoordinationStore closed = FailFastStore.of(newStore(), TIME);
        final String key = key("closed");
        final LeaseAttempt.Acquired taken = assertInstanceOf(LeaseAttempt.Acquired.class,
                done(closed.tryAcquire(key, "owner-a/run-1", Duration.ofMinutes(1))));
        closed.close();
        closed.close();

        assertFails(closed.get(key("closed-value")));
        assertFails(closed.compareAndSet(key("closed-value"), CoordinationStore.INITIAL_VERSION, "v"));
        assertFails(closed.awaitChange(key("closed-value"), CoordinationStore.INITIAL_VERSION, shortWait()));
        assertFails(closed.tryAcquire(key, "owner-b/run-1", Duration.ofMinutes(1)));
        assertFails(closed.renew(key, taken.handle(), Duration.ofMinutes(1)));
        assertFails(closed.release(key, taken.handle()));
    }

    @Test
    void endsAWaitOutstandingWhenClosed() {
        final CoordinationStore closing = newStore();
        final CompletableFuture<Entry> waiting = FailFastStore.of(closing, TIME)
                .awaitChange(key("closing"), CoordinationStore.INITIAL_VERSION, Duration.ofHours(1))
                .toCompletableFuture();

        closing.close();

        assertThrows(CompletionException.class, () -> done(waiting),
                "A wait outstanding when the store closes has to be told");
    }

    // ---- helpers --------------------------------------------------------------------------------

    /**
     * @param stage what a store answered
     * @param <T>   what it answers with
     * @return the answer
     */
    protected static <T> T done(final CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (final ExecutionException failed) {
            throw new CompletionException(failed.getCause());
        } catch (final Exception failed) {
            throw new AssertionError("The store did not answer", failed);
        }
    }

    private static boolean succeeded(final CompletableFuture<Boolean> written) {
        try {
            return done(written);
        } catch (final CompletionException refused) {
            return false;
        }
    }

    private static void assertFails(final CompletionStage<?> stage) {
        assertThrows(CompletionException.class, () -> done(stage), "The store must refuse");
    }
}
