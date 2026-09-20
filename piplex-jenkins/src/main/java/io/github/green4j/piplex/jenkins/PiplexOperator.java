/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import hudson.AbortException;
import io.github.green4j.piplex.Piplex;

import java.io.Serializable;

/**
 * The live operator identity named by a {@code withPiplexOperator} block's serializable context.
 *
 * <p>Its presence in the context is what lets an operator step run at all. A step that finds none is
 * outside the block, and is refused rather than quietly falling back to this controller's own
 * identity: a silent fallback would be a way to make an operator write without it being visible in
 * the Jenkinsfile that one was being made.
 *
 * <p>The primitives behind it hold an open client and cannot be serialized, so the context carries an
 * id resolved through {@link OperatorStepExecution}. Nothing resolves after a restart, which ends the
 * block rather than resuming it.
 */
final class PiplexOperator implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String id;
    private final String clientId;

    PiplexOperator(final String id, final String clientId) {
        this.id = id;
        this.clientId = clientId;
    }

    /**
     * @return the discas identity these writes are made under, which is what an ACL grants
     */
    String clientId() {
        return clientId;
    }

    /**
     * The primitives to write through, as the identity this block opened.
     *
     * @return the primitives
     * @throws AbortException if the block is over or did not survive a restart, which is the answer
     *                        rather than a write made as somebody else
     */
    Piplex piplex() throws AbortException {
        final Piplex open = OperatorStepExecution.sessionOf(id);
        if (open == null) {
            throw new AbortException("piplex: the operator block acting as '" + clientId + "' is no "
                    + "longer open, so this operation has no identity to run under. A block does not "
                    + "survive a controller restart; run the job again");
        }
        return open;
    }
}
