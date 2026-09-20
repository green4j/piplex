/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex;

/**
 * Where a host writes the mark that says which process is using an owner id.
 *
 * <p>Nothing in the core writes it -- a host that wants the duplicate-owner check does. The key
 * shape lives here anyway, because it is the one piece of it two modules have to agree on: the host
 * that writes the mark, and whatever composes the grant that lets it.
 */
public final class Instances {

    private static final String KIND = "instances";

    private Instances() {
    }

    /**
     * @param environment the host's own environment, never a step's: an owner id names one machine
     *                    however many environments its work runs in
     * @param ownerId     the identity being marked
     * @return the store key the mark is written at
     */
    public static String keyOf(final Environment environment, final String ownerId) {
        return environment.prefixOf(KIND) + ownerId;
    }

    /**
     * @param environment the host's own environment
     * @return the prefix every such key starts with, which is what an ACL grants
     */
    public static String prefixOf(final Environment environment) {
        return environment.prefixOf(KIND);
    }
}
