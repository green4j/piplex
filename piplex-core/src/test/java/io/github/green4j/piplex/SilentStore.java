/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex;

import io.github.green4j.piplex.store.CoordinationStore;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A store which takes every call and answers none, the way a broken adapter would: no failure, no
 * timeout of its own, nothing.
 */
public final class SilentStore {

    private final List<CompletableFuture<?>> taken = new CopyOnWriteArrayList<>();
    private final CoordinationStore store;

    /**
     * @param bound what the store declares as its response bound
     */
    public SilentStore(final Duration bound) {
        final InvocationHandler handler = (proxy, method, args) -> {
            if (method.getReturnType() == CompletionStage.class) {
                final CompletableFuture<Object> never = new CompletableFuture<>();
                taken.add(never);
                return never;
            }
            if (method.getName().equals("responseBound")) {
                return bound;
            }
            return null;
        };
        store = (CoordinationStore) Proxy.newProxyInstance(
                CoordinationStore.class.getClassLoader(), new Class<?>[] {CoordinationStore.class}, handler);
    }

    /**
     * @return the store
     */
    public CoordinationStore store() {
        return store;
    }

    /**
     * @return every stage handed out so far
     */
    public List<CompletableFuture<?>> taken() {
        return taken;
    }
}
