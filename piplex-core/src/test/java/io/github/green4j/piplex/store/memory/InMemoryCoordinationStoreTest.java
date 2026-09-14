/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.store.memory;

import io.github.green4j.piplex.ManualTime;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.Entry;
import io.github.green4j.piplex.store.LeaseAttempt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryCoordinationStoreTest {

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final Duration WAIT = Duration.ofSeconds(30);

    private ManualTime time;
    private InMemoryCoordinationStore store;

    @BeforeEach
    void setUp() {
        time = new ManualTime();
        store = new InMemoryCoordinationStore(time);
    }

    @Test
    void createsOnlyOnceAgainstTheInitialVersion() {
        assertTrue(done(store.compareAndSet("k", CoordinationStore.INITIAL_VERSION, "first")));
        assertFalse(done(store.compareAndSet("k", CoordinationStore.INITIAL_VERSION, "second")));
        assertEquals("first", done(store.get("k")).value());
    }

    @Test
    void rejectsAStaleExpectedVersion() {
        done(store.compareAndSet("k", CoordinationStore.INITIAL_VERSION, "first"));
        final Entry seen = done(store.get("k"));
        assertTrue(done(store.compareAndSet("k", seen.version(), "second")));
        assertFalse(done(store.compareAndSet("k", seen.version(), "third")));
        assertEquals("second", done(store.get("k")).value());
    }

    @Test
    void returnsAtOnceWhenTheKeyAlreadyMovedOn() {
        done(store.compareAndSet("k", CoordinationStore.INITIAL_VERSION, "v"));
        final CompletionStage<Entry> waited =
                store.awaitChange("k", CoordinationStore.INITIAL_VERSION, WAIT);
        assertEquals("v", done(waited).value());
        assertEquals(0, time.pending(), "a wait which returned at once must leave no timeout behind");
    }

    @Test
    void coalescesWritesMadeWhileWaiting() {
        done(store.compareAndSet("k", CoordinationStore.INITIAL_VERSION, "one"));
        final Entry seen = done(store.get("k"));

        final CompletableFuture<Entry> waited =
                store.awaitChange("k", seen.version(), WAIT).toCompletableFuture();
        assertFalse(waited.isDone());

        done(store.compareAndSet("k", seen.version(), "two"));
        final Entry afterTwo = done(store.get("k"));
        done(store.compareAndSet("k", afterTwo.version(), "three"));

        // The intermediate value is simply not seen. Anything counting events rather than comparing
        // state breaks here, which is the point of asserting it.
        assertTrue(waited.isDone());
        assertEquals("two", waited.join().value());
    }

    @Test
    void timesOutReturningWhateverIsInForce() {
        done(store.compareAndSet("k", CoordinationStore.INITIAL_VERSION, "v"));
        final Entry seen = done(store.get("k"));

        final CompletableFuture<Entry> waited =
                store.awaitChange("k", seen.version(), WAIT).toCompletableFuture();
        assertFalse(waited.isDone());

        time.advance(WAIT);

        assertTrue(waited.isDone());
        assertEquals(seen.version(), waited.join().version());
    }

    @Test
    void grantsTheLeaseToOneOwnerAtATime() {
        final LeaseAttempt first = done(store.tryAcquire("lock", "euc1-blue", TTL));
        final LeaseAttempt second = done(store.tryAcquire("lock", "eus1-blue", TTL));

        assertInstanceOf(LeaseAttempt.Acquired.class, first);
        final LeaseAttempt.HeldByOther rejected = assertInstanceOf(LeaseAttempt.HeldByOther.class, second);
        assertEquals("euc1-blue", rejected.ownerId());
    }

    @Test
    void tellsTheHolderItAlreadyHoldsRatherThanRefusingIt() {
        done(store.tryAcquire("lock", "euc1-blue", TTL));
        // What an acquire whose outcome was never learned looks like on retry: success, not contention.
        assertInstanceOf(LeaseAttempt.HeldBySelf.class, done(store.tryAcquire("lock", "euc1-blue", TTL)));
    }

    @Test
    void handsTheLeaseOverOnlyOnceItHasLapsed() {
        final var first = assertInstanceOf(LeaseAttempt.Acquired.class,
                done(store.tryAcquire("lock", "euc1-blue", TTL)));

        time.advance(TTL.minusSeconds(1));
        assertInstanceOf(LeaseAttempt.HeldByOther.class, done(store.tryAcquire("lock", "eus1-blue", TTL)));

        time.advance(Duration.ofSeconds(2));
        final var second = assertInstanceOf(LeaseAttempt.Acquired.class,
                done(store.tryAcquire("lock", "eus1-blue", TTL)));

        assertTrue(second.handle().fencingToken() > first.handle().fencingToken(),
                "the fencing token must increase on every acquisition");
        assertNotEquals(first.handle(), second.handle());
    }

    @Test
    void ignoresTheWallClockEntirely() {
        final var granted = assertInstanceOf(LeaseAttempt.Acquired.class,
                done(store.tryAcquire("lock", "euc1-blue", TTL)));

        // An NTP correction, or an operator setting the date. No time has passed.
        time.jump(Duration.ofHours(5L));
        assertInstanceOf(LeaseAttempt.HeldByOther.class, done(store.tryAcquire("lock", "eus1-blue", TTL)),
                "a lease must not lapse because a wall clock moved forward");
        assertTrue(done(store.renew("lock", granted.handle(), TTL)));

        time.jump(Duration.ofHours(-9L));
        assertInstanceOf(LeaseAttempt.HeldByOther.class, done(store.tryAcquire("lock", "eus1-blue", TTL)));

        // And it still lapses when time actually passes.
        time.advance(TTL.plusSeconds(1L));
        assertInstanceOf(LeaseAttempt.Acquired.class, done(store.tryAcquire("lock", "eus1-blue", TTL)));
    }

    @Test
    void refusesToRenewALapsedLease() {
        final var granted = assertInstanceOf(LeaseAttempt.Acquired.class,
                done(store.tryAcquire("lock", "euc1-blue", TTL)));

        assertTrue(done(store.renew("lock", granted.handle(), TTL)));

        time.advance(TTL.plusSeconds(1));
        assertFalse(done(store.renew("lock", granted.handle(), TTL)),
                "a holder learns its lease lapsed on its next call, and not a moment sooner");
    }

    @Test
    void letsALapsedLeaseStillBeReleased() {
        final var granted = assertInstanceOf(LeaseAttempt.Acquired.class,
                done(store.tryAcquire("lock", "euc1-blue", TTL)));
        time.advance(TTL.plusSeconds(1));
        done(store.release("lock", granted.handle()));
        assertInstanceOf(LeaseAttempt.Acquired.class, done(store.tryAcquire("lock", "eus1-blue", TTL)));
    }

    @Test
    void endsAWaitItCanNoLongerAnswerWhenItIsClosed() {
        final CompletableFuture<Entry> waiting =
                store.awaitChange("piplex/designated/eod", CoordinationStore.INITIAL_VERSION,
                        Duration.ofHours(4)).toCompletableFuture();
        assertFalse(waiting.isDone());

        store.close();

        // A closed client ends its outstanding requests rather than leaving them to a timeout that is
        // never coming, which in a test is the difference between a message and a hang.
        assertTrue(waiting.isCompletedExceptionally(),
                "a wait outstanding when the store closes has to be told");
    }

    private static <T> T done(final CompletionStage<T> stage) {
        final CompletableFuture<T> future = stage.toCompletableFuture();
        assertTrue(future.isDone(), "the in-memory store must answer without waiting");
        return future.join();
    }
}
