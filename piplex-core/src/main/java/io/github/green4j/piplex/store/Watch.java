/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.store;

/**
 * How soon a {@link CoordinationStore#awaitChange wait} must hear of a change -- for a store whose
 * watches cost something per look, and so can look less often where lateness is cheap.
 */
public enum Watch {

    /**
     * The caller acts on the change as soon as it hears: a run in flight stops on it. Every moment late
     * is a moment two holders may both believe they own the work.
     */
    URGENT,

    /**
     * The caller only paces a wait by it and re-reads on its own schedule anyway, so hearing late costs
     * time, not safety.
     */
    BACKGROUND
}
