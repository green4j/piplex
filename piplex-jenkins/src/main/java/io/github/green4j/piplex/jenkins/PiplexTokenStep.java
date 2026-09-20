/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import hudson.Extension;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.Set;

/**
 * {@code piplexToken} -- the fencing token currently held by the enclosing
 * {@code piplexExclusive} block.
 *
 * <p>{@code PIPLEX_FENCING_TOKEN} contains the same token and is expanded when the body starts. This
 * step checks the live admission and fails outside an exclusive block or after ownership is lost. A
 * resumed body continues only when its lease is retaken with the same token.
 */
public final class PiplexTokenStep extends Step {

    /**
     * Takes nothing: what it answers about is the block it is written in.
     */
    @DataBoundConstructor
    public PiplexTokenStep() {
    }

    @Override
    public StepExecution start(final StepContext context) {
        return new Execution(context);
    }

    /**
     * Reads the token, on the thread that asked. Nothing here waits for anything.
     */
    private static final class Execution extends SynchronousStepExecution<Long> {

        private static final long serialVersionUID = 1L;

        private Execution(final StepContext context) {
            super(context);
        }

        @Override
        protected Long run() throws Exception {
            return getContext().get(PiplexOwnership.class).fencingToken();
        }
    }

    /**
     * Registers the step with Jenkins.
     */
    @Extension
    @Symbol("piplexToken")
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "piplexToken";
        }

        @Override
        public String getDisplayName() {
            return "The fencing token piplex admitted this block under";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            // PiplexOwnership is what confines this to piplexExclusive. Asking Jenkins for it is what
            // produces the sentence somebody using it outside needs: the context is missing, and the
            // step which provides it is named.
            return Set.of(PiplexOwnership.class);
        }
    }
}
