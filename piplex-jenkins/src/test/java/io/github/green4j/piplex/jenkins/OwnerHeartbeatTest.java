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
import io.github.green4j.piplex.store.Entry;
import io.github.green4j.piplex.store.memory.InMemoryCoordinationStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnerHeartbeatTest {

    private static final String OWNER = "euc1-blue";

    private final SteppedTime time = new SteppedTime();
    private final InMemoryCoordinationStore store = new InMemoryCoordinationStore(time);

    @Test
    void saysNothingWhileItIsAlone() throws Exception {
        final OwnerHeartbeat alone = new OwnerHeartbeat(time);
        for (int i = 0; i < 5; i++) {
            beat(alone, OWNER);
        }
        assertNull(alone.duplicated());
    }

    @Test
    void saysNothingAboutTheMarkItsOwnPreviousProcessLeft() throws Exception {
        beat(new OwnerHeartbeat(time), OWNER);

        // The controller restarted: the key still holds the mark of the process before.
        final OwnerHeartbeat restarted = new OwnerHeartbeat(time);
        beat(restarted, OWNER);
        beat(restarted, OWNER);

        assertNull(restarted.duplicated(), "A restart must not look like a clone");
    }

    @Test
    void warnsAboutAnotherProcessUnderTheSameOwnerIdWhileItIsThere() throws Exception {
        final OwnerHeartbeat original = new OwnerHeartbeat(time);
        final OwnerHeartbeat clone = new OwnerHeartbeat(time);

        beat(original, OWNER);
        beat(clone, OWNER);
        beat(original, OWNER);
        beat(clone, OWNER);

        assertEquals(OWNER, original.duplicated());
        assertEquals(OWNER, clone.duplicated());

        // The clone was shut down, and its mark is overwritten for good.
        beat(original, OWNER);
        time.advance(OwnerHeartbeat.WARN_FOR);
        beat(original, OWNER);

        assertNull(original.duplicated(), "Nothing to warn about once the other process has gone");
    }

    @Test
    void startsOverWhenTheOwnerIdChanges() throws Exception {
        final OwnerHeartbeat original = new OwnerHeartbeat(time);
        final OwnerHeartbeat clone = new OwnerHeartbeat(time);
        beat(original, OWNER);
        beat(clone, OWNER);
        beat(original, OWNER);

        // The operator fixed it by renaming this controller.
        beat(original, "euc1-green");

        assertNull(original.duplicated());
        final Entry written = store.get(OwnerHeartbeat.keyOf(Environment.DEFAULT, "euc1-green"))
                .toCompletableFuture().join();
        assertTrue(written.exists());
    }

    @Test
    void saysNothingAboutTheSameOwnerIdInAnotherEnvironment() throws Exception {
        // Two deployments, not one duplicated. Sharing a cluster between production and uat is the
        // ordinary reason a controller's owner id turns up twice, and warning about it would make the
        // check cry wolf in exactly the setup the environment segment exists for.
        final OwnerHeartbeat prod = new OwnerHeartbeat(time);
        final OwnerHeartbeat uat = new OwnerHeartbeat(time);
        final Environment other = Environment.of("uat");

        beat(prod, OWNER);
        uat.tick(store, other, OWNER).toCompletableFuture().get(10L, TimeUnit.SECONDS);
        beat(prod, OWNER);
        uat.tick(store, other, OWNER).toCompletableFuture().get(10L, TimeUnit.SECONDS);

        assertNull(prod.duplicated());
        assertNull(uat.duplicated());
        assertTrue(store.get(OwnerHeartbeat.keyOf(Environment.DEFAULT, OWNER))
                .toCompletableFuture().join().exists());
        assertTrue(store.get(OwnerHeartbeat.keyOf(other, OWNER)).toCompletableFuture().join().exists(),
                "Each environment keeps its own mark for the same owner id");
    }

    @Test
    void startsOverWhenTheEnvironmentChanges() throws Exception {
        final OwnerHeartbeat original = new OwnerHeartbeat(time);
        final OwnerHeartbeat clone = new OwnerHeartbeat(time);
        beat(original, OWNER);
        beat(clone, OWNER);
        beat(original, OWNER);
        assertEquals(OWNER, original.duplicated());

        // The operator fixed it by moving this controller to its own environment instead.
        original.tick(store, Environment.of("uat"), OWNER).toCompletableFuture().get(10L, TimeUnit.SECONDS);

        assertNull(original.duplicated(), "A new environment is a new key, with nothing seen on it");
    }

    @Test
    void saysSoWhenItsMarkCannotBeWritten() throws Exception {
        final OwnerHeartbeat refused = new OwnerHeartbeat(time);
        final CoordinationStore readOnly =
                refusingWrites(CompletableFuture.failedFuture(new IllegalStateException("Not authorized")));

        refused.tick(readOnly, Environment.DEFAULT, OWNER).toCompletableFuture().get(10L, TimeUnit.SECONDS);
        assertNull(refused.silent(), "One refused beat is not yet a pattern");

        while (!time.passed(OwnerHeartbeat.SILENT_AFTER)) {
            time.advance(OwnerHeartbeat.EVERY);
            refused.tick(readOnly, Environment.DEFAULT, OWNER).toCompletableFuture().get(10L, TimeUnit.SECONDS);
        }
        assertEquals(OWNER, refused.silent());

        beat(refused, OWNER);
        assertNull(refused.silent(), "A beat that lands ends it");
    }

    @Test
    void saysNothingOnceItHasStoppedTrying() throws Exception {
        final OwnerHeartbeat refused = new OwnerHeartbeat(time);
        final CoordinationStore readOnly = refusingWrites(CompletableFuture.completedFuture(false));
        refused.tick(readOnly, Environment.DEFAULT, OWNER).toCompletableFuture().get(10L, TimeUnit.SECONDS);
        time.advance(OwnerHeartbeat.SILENT_AFTER);
        refused.tick(readOnly, Environment.DEFAULT, OWNER).toCompletableFuture().get(10L, TimeUnit.SECONDS);
        assertEquals(OWNER, refused.silent());

        // The store was unconfigured: nothing asks any more, so nothing is failing either.
        time.advance(OwnerHeartbeat.SILENT_AFTER);
        assertNull(refused.silent());
    }

    private CoordinationStore refusingWrites(final CompletableFuture<Boolean> answer) {
        return (CoordinationStore) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {CoordinationStore.class},
                (proxy, method, args) -> method.getName().equals("compareAndSet")
                        ? answer
                        : method.invoke(store, args));
    }

    private void beat(final OwnerHeartbeat heartbeat, final String owner) throws Exception {
        heartbeat.tick(store, Environment.DEFAULT, owner).toCompletableFuture().get(10L, TimeUnit.SECONDS);
    }

    /**
     * A clock only the test moves. Timers are taken and never fire: the store here always answers.
     */
    private static final class SteppedTime implements TimeSource {

        private long nanos;

        void advance(final Duration by) {
            nanos += by.toNanos();
        }

        boolean passed(final Duration since) {
            return nanos - since.toNanos() >= 0L;
        }

        @Override
        public long nanos() {
            return nanos;
        }

        @Override
        public Instant wallTime() {
            return Instant.EPOCH;
        }

        @Override
        public Cancellable schedule(final Duration delay, final Runnable action) {
            return () -> { };
        }
    }
}
