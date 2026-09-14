/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.example;

import io.github.green4j.piplex.TimeSource;
import io.github.green4j.piplex.Piplex;
import io.github.green4j.piplex.observe.TextPiplexObserver;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.memory.InMemoryCoordinationStore;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Several Jenkins controllers, pretended in one JVM.
 *
 * <p>In production each of these is a separate machine in a separate region, and the only thing they
 * share is the store. That is what is reproduced here: one store, and a separate {@link Piplex} per
 * controller, each with its own view of the world. No example reaches across from one controller into
 * another, because in production there would be nothing to reach with.
 *
 * <p>The store is the in-memory one unless another is passed in, so the examples run with no cluster
 * and no setup. Give them a {@code DiscasCoordinationStore} instead and the same code runs across
 * regions -- which is the whole point of the store being an argument rather than a discovery.
 */
final class Controllers implements AutoCloseable {

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, runnable -> {
                final Thread thread = new Thread(runnable, "piplex-example");
                thread.setDaemon(true);
                return thread;
            });

    private final TimeSource time = TimeSource.of(scheduler);
    private final CoordinationStore store;
    private final boolean ownsStore;

    Controllers() {
        this.store = new InMemoryCoordinationStore(time);
        this.ownsStore = true;
    }

    Controllers(final CoordinationStore sharedStore) {
        this.store = sharedStore;
        this.ownsStore = false;
    }

    /**
     * @param ownerId the deployment's identity, the thing a designation names
     * @return the primitives as that controller sees them, logging under its name
     */
    Piplex controller(final String ownerId) {
        return new Piplex(store, time,
                new TextPiplexObserver(line -> System.out.println("  [" + ownerId + "] " + line)));
    }

    /**
     * @return the primitives as the operator's tooling sees them -- the same store, no run of its own
     */
    Piplex operator() {
        return new Piplex(store, time,
                new TextPiplexObserver(line -> System.out.println("  [operator] " + line)));
    }

    @Override
    public void close() {
        if (ownsStore) {
            store.close();
        }
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
