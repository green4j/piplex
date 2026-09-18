/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex;

import java.util.Objects;

/**
 * Which set of orchestrations a key belongs to -- production, uat, a developer's own.
 *
 * <p>One cluster can hold several of them. Without this every name would be shared by all of them: one
 * {@code eod} milestone, one {@code eod} lease, one designation deciding who runs the work everywhere
 * at once. The environment is the segment that keeps them apart, and it is the whole of the mechanism
 * -- there is nothing else separating two environments on one cluster.
 *
 * <p>It is also where the key grammar lives. Every key piplex writes is
 * {@code piplex/<environment>/<kind>/<name>}, and the two leading segments are composed here and
 * nowhere else.
 *
 * <p>Like the store, it is passed in and never discovered: no system property, no variable of the
 * operating system's own. A host which reads its environment from the world around it is a host whose
 * keys change when that world does.
 *
 * @param value the environment, never blank and never containing {@code '/'}
 */
public record Environment(String value) {

    /**
     * Where piplex keeps its own records. Every key it writes is under this, and an
     * {@code activeKey} -- written by somebody else -- may not be.
     */
    public static final String ROOT = "piplex/";

    /** What a host which names no environment works in. */
    public static final Environment DEFAULT = new Environment("default");

    /**
     * @param value the environment
     */
    public Environment {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("An environment must not be blank");
        }
        if (value.indexOf('/') >= 0) {
            // A '/' here does not make a second environment, it makes a key of a different shape: the
            // segment after it is read as the kind of record, so 'a/b' quietly writes its milestones
            // where an environment named 'a' keeps something else entirely.
            throw new IllegalArgumentException(
                    "An environment must not contain '/', because that is what separates it from the "
                            + "kind of record and the name, but got '" + value + "'");
        }
    }

    /**
     * An environment from its name.
     *
     * @param value the environment
     * @return the environment
     */
    public static Environment of(final String value) {
        return new Environment(value);
    }

    /**
     * The environment named, or {@link #DEFAULT} where none is.
     *
     * <p>For a host reading a setting somebody left empty, which is the ordinary case: one
     * environment, never named, and its keys still have to go somewhere.
     *
     * @param value the environment, or {@code null} or blank where none was named
     * @return the environment to use
     */
    public static Environment orDefault(final String value) {
        return value == null || value.isBlank() ? DEFAULT : of(value);
    }

    /**
     * Where one kind of record is kept in this environment, for an operator reading the store by hand
     * and for the primitives to hold on to.
     *
     * <p>Composed once and kept, not built per key: it is the same three segments for the life of a
     * primitive.
     *
     * @param kind what is kept there -- {@code milestone}, {@code enabled}, and so on
     * @return the prefix every such key starts with, ending in {@code '/'}
     */
    public String prefixOf(final String kind) {
        Objects.requireNonNull(kind, "kind");
        return ROOT + value + '/' + kind + '/';
    }

    @Override
    public String toString() {
        return value;
    }
}
