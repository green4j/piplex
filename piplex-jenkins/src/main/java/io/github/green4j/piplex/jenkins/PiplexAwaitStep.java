/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import hudson.AbortException;
import hudson.Extension;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.github.green4j.piplex.Generation;
import io.github.green4j.piplex.milestone.AwaitResult;
import io.github.green4j.piplex.milestone.Milestone;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * {@code piplexAwait} -- waits without an executor until a milestone reaches a generation.
 *
 * <p>The timeout defaults to one hour and starts over after a controller restart. Timeout fails the
 * build unless {@code skipOnTimeout} is set, in which case the result is {@code NOT_BUILT}.
 */
public final class PiplexAwaitStep extends Step {

    private final String key;
    private final String generation;
    private String environment;
    private String timeout;
    private boolean skipOnTimeout;

    /**
     * @param key        the milestone to wait for
     * @param generation the generation this build needs
     */
    @DataBoundConstructor
    public PiplexAwaitStep(final String key, final String generation) {
        this.key = key;
        this.generation = generation;
    }

    public String getKey() {
        return key;
    }

    public String getGeneration() {
        return generation;
    }

    public String getTimeout() {
        return timeout;
    }

    /**
     * @param value how long to wait; one hour by default
     */
    @DataBoundSetter
    public void setTimeout(final String value) {
        this.timeout = value;
    }

    public boolean isSkipOnTimeout() {
        return skipOnTimeout;
    }

    /**
     * @param value whether running out of time should end the build as not built rather than fail it
     */
    @DataBoundSetter
    public void setSkipOnTimeout(final boolean value) {
        this.skipOnTimeout = value;
    }

    /**
     * @param value which set of orchestrations this work belongs to; leave unset for the controller's
     *              default, set in Manage Jenkins &gt; System
     */
    @DataBoundSetter
    public void setEnvironment(final String value) {
        this.environment = value;
    }

    public String getEnvironment() {
        return environment;
    }

    @Override
    public StepExecution start(final StepContext context) {
        return new Execution(context, this);
    }

    /**
     * Registers the step with Jenkins.
     */
    @Extension
    @Symbol("piplexAwait")
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "piplexAwait";
        }

        @Override
        public String getDisplayName() {
            return "Wait for a piplex milestone";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, TaskListener.class);
        }
    }

    private static final class Execution extends StepExecution {

        private static final long serialVersionUID = 1L;
        private static final Duration DEFAULT_TIMEOUT = Duration.ofHours(1L);

        private final String key;
        private final String generation;
        // Written down with the rest: a resumed wait must look in the environment it began in.
        private final String environment;
        private final String timeout;
        private final boolean skipOnTimeout;

        // Not carried across a restart: onResume asks again, and the answer that asking again waits for
        // is one nothing has stopped yet.
        private transient boolean answered;
        private transient volatile CompletableFuture<AwaitResult> waiting;

        Execution(final StepContext context, final PiplexAwaitStep step) {
            super(context);
            this.key = step.getKey();
            this.generation = step.getGeneration();
            this.environment = step.getEnvironment();
            this.timeout = step.getTimeout();
            this.skipOnTimeout = step.isSkipOnTimeout();
        }

        @Override
        public boolean start() throws Exception {
            await();
            return false;
        }

        /**
         * Ends the step and the wait behind it, unless the answer it was waiting for got there first.
         *
         * @param cause why the build is stopping
         */
        @Override
        public void stop(final Throwable cause) throws Exception {
            if (answering()) {
                final CompletableFuture<AwaitResult> asked = waiting;
                if (asked != null) {
                    asked.cancel(false);
                }
                super.stop(cause);
            }
        }

        /**
         * Claims the right to answer the context, once.
         *
         * <p>A cancelled wait still finishes the watch it is in, so the answer still coming and the
         * build being stopped are two outcomes racing for one step. A flag read and then acted on
         * leaves the gap between the two open, which is an aborted build getting a second outcome after
         * somebody pressed the button. Whichever asks first here wins, and the other one does nothing.
         *
         * @return whether this caller is the one that gets to answer
         */
        private synchronized boolean answering() {
            if (answered) {
                return false;
            }
            answered = true;
            return true;
        }


        @Override
        public void onResume() {
            // A waiter holds no position of its own -- it knows which generation it needs and reads the
            // key -- so resuming is the same call as starting. The timeout does start over, which is the
            // one thing a restart changes here.
            try {
                await();
            } catch (final Exception failed) {
                if (answering()) {
                    getContext().onFailure(failed);
                }
            }
        }

        private void await() throws Exception {
            final TaskListener listener = getContext().get(TaskListener.class);
            final CompletableFuture<AwaitResult> asked = PiplexConfiguration.require()
                    .piplexFor(listener, environment).milestones()
                    .awaitAtLeast(key, Generation.of(generation),
                            Durations.parse(timeout, DEFAULT_TIMEOUT, "timeout"))
                    .toCompletableFuture();
            waiting = asked;
            asked.whenComplete((result, error) -> {
                if (!answering()) {
                    return;
                }
                if (error != null) {
                    getContext().onFailure(error);
                } else if (result.outcome() == AwaitResult.Outcome.REACHED) {
                    getContext().onSuccess(result.inForce().generation().value());
                } else {
                    getContext().onFailure(timedOut(result));
                }
            });
        }

        private Throwable timedOut(final AwaitResult result) {
            final Milestone inForce = result.inForce();
            final String reached = inForce == null ? null : inForce.generation().value();
            if (skipOnTimeout) {
                return new FlowInterruptedException(Result.NOT_BUILT, false,
                        new PiplexInterruption.MilestoneMissing(key, generation, reached));
            }
            return new AbortException("piplex: waited for '" + key + "' to reach " + generation
                    + " but it is at " + (reached == null ? "nothing yet" : reached));
        }
    }
}
