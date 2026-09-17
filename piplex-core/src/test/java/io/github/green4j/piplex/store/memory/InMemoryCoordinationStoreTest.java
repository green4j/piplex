/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.store.memory;

import io.github.green4j.piplex.ManualTime;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.CoordinationStoreContract;
import io.github.green4j.piplex.store.LeaseAttempt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryCoordinationStoreTest extends CoordinationStoreContract {

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final Duration WAIT = Duration.ofSeconds(30);

    private ManualTime time;
    private InMemoryCoordinationStore store;

    @BeforeEach
    void setUp() {
        time = new ManualTime();
        store = new InMemoryCoordinationStore(time);
    }

    @Override
    protected CoordinationStore subject() {
        return store;
    }

    @Override
    protected CoordinationStore newStore() {
        return new InMemoryCoordinationStore(time);
    }

    @Override
    protected String key(final String name) {
        return name;
    }

    @Override
    protected Duration shortLease() {
        return TTL;
    }

    @Override
    protected Duration shortWait() {
        return WAIT;
    }

    @Override
    protected void letPass(final Duration duration) {
        time.advance(duration);
    }

    @Test
    void ignoresTheWallClockEntirely() {
        final var granted = assertInstanceOf(LeaseAttempt.Acquired.class,
                done(store.tryAcquire("lock", "euc1-blue", TTL)));

        // An NTP correction, or an operator setting the date. No time has passed.
        time.jump(Duration.ofHours(5L));
        assertInstanceOf(LeaseAttempt.HeldByOther.class, done(store.tryAcquire("lock", "eus1-blue", TTL)),
                "A lease must not lapse because a wall clock moved forward");
        assertTrue(done(store.renew("lock", granted.handle(), TTL)));

        time.jump(Duration.ofHours(-9L));
        assertInstanceOf(LeaseAttempt.HeldByOther.class, done(store.tryAcquire("lock", "eus1-blue", TTL)));

        // And it still lapses when time actually passes.
        time.advance(TTL.plusSeconds(1L));
        assertInstanceOf(LeaseAttempt.Acquired.class, done(store.tryAcquire("lock", "eus1-blue", TTL)));
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
    void letsAKeyBeWrittenOnceTheLeaseOnItHasLapsed() {
        assertInstanceOf(LeaseAttempt.Acquired.class, done(store.tryAcquire("k", "euc1-blue", TTL)));
        time.advance(TTL.plusSeconds(1));

        // A released lease is kept, as an expired one, so that the fencing token goes on climbing. What
        // a write collides with is a lease somebody could still be acting under, not the record of one.
        assertTrue(done(store.compareAndSet("k", CoordinationStore.INITIAL_VERSION, "a value")));
    }
}
