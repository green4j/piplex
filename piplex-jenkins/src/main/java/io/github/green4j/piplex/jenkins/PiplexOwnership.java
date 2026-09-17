/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import hudson.AbortException;
import io.github.green4j.piplex.exclusive.Admitted;

import java.io.Serializable;

/**
 * The live ownership named by an exclusive block's serializable context.
 *
 * <p>The admission itself cannot survive a restart, so the context carries an id resolved through
 * {@link ExclusiveStepExecution}. A resumed body remains valid only when the step retakes the same
 * fencing token; a different token aborts the body.
 */
final class PiplexOwnership implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String key;
    private final String id;

    PiplexOwnership(final String key, final String id) {
        this.key = key;
        this.id = id;
    }

    /**
     * @return what is being competed for
     */
    String key() {
        return key;
    }

    /**
     * The token to present to whatever the block is protecting.
     *
     * <p>It only protects anything where the resource itself remembers the highest token it has seen
     * and refuses anything lower. Where it does not, this is a number in a log.
     *
     * @return the fencing token of the lease in force
     * @throws AbortException if this block does not hold the ownership any more, which is the answer
     *                        rather than a stale token
     */
    long fencingToken() throws AbortException {
        final Admitted granted = ExclusiveStepExecution.ownershipOf(id);
        if (granted == null || !granted.isHeld()) {
            throw new AbortException("piplex: the ownership of '" + key + "' is not held here any more, "
                    + "so there is no fencing token to give. The block is being stopped; whatever this "
                    + "was about to do must not happen");
        }
        return granted.fencingToken();
    }
}
