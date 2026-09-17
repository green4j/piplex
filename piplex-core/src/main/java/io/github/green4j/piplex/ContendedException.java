/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex;

/**
 * A compare-and-set lost to another writer too many times running.
 *
 * <p>Every key piplex writes is written by a read-compare-write loop, and losing the compare once is
 * ordinary -- somebody else got there first, so read again and reconsider. Losing it repeatedly is not:
 * on these keys writers are few and writes are rare, so a run of losses says something is wrong that
 * another attempt will not fix. Two producers believing they own the same milestone, or two operators
 * fighting over a designation, are the usual answers.
 *
 * <p>Retrying is bounded rather than endless on purpose: a caller told that it failed can decide what
 * to do, while a caller left spinning cannot.
 */
public final class ContendedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String key;

    /**
     * @param key      the key written to
     * @param attempts how many times it was tried
     */
    public ContendedException(final String key, final int attempts) {
        super("Key '" + key + "' lost the compare " + attempts + " times running");
        this.key = key;
    }

    /**
     * @return the key written to
     */
    public String key() {
        return key;
    }
}
