/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex;

/**
 * A key holding something piplex did not write there, or cannot read back.
 *
 * <p>Always one operator error -- a hand-edited value, a key reused for something else -- and which
 * operation happened to find it is not what the person fixing it needs to be told. So the sentence is
 * built here and said the same way wherever a key will not parse.
 */
public final class UnreadableKeyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String key;

    /**
     * @param key         the key which could not be read
     * @param what        what that key is
     * @param notReadable why it could not be read
     */
    public UnreadableKeyException(final String key, final String what, final Throwable notReadable) {
        super(detail(key, what, notReadable), notReadable);
        this.key = key;
    }

    /**
     * The one sentence an unreadable key gets, wherever it is found.
     *
     * @param key         the key which could not be read
     * @param what        what that key is
     * @param notReadable why it could not be read
     * @return what somebody would have to be told to fix it
     */
    public static String detail(final String key, final String what, final Throwable notReadable) {
        return "the " + what + " at '" + key + "' cannot be read: " + notReadable.getMessage();
    }

    /**
     * @return the key which could not be read
     */
    public String key() {
        return key;
    }
}
