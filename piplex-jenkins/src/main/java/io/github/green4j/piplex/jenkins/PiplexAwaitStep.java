/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import edu.umd.cs.findbugs.annotations.NonNull;
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

/**
 * {@code piplexAwait} -- wait until the data this build needs exists.
 *
 * <pre>
 * stage('Wait for EOD') {
 *     steps { piplexAwait key: 'data/euc1', generation: env.BUSINESS_DATE, timeout: '90m' }
 * }
 * </pre>
 *
 * <p>This is what replaces "end-of-day should be finished by half past five". It holds no executor while
 * it waits, so an hour of waiting costs a {@code FlowExecution} in memory, and it compares state rather
 * than catching an event, so a controller that restarts mid-wait simply looks again and carries on.
 *
 * <p>Running out of time fails the build by default, and should: something that was supposed to arrive
 * did not, and the reason it says so is that the alternative -- carrying on -- is how a day gets
 * imported half-empty without anybody noticing. Set {@code skipOnTimeout} when not running is genuinely
 * the right answer, and the build ends as {@code NOT_BUILT} with what was actually there recorded on it.
 */
public final class PiplexAwaitStep extends Step {

    private final String key;
    private final String generation;
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
        @NonNull
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
        private final String timeout;
        private final boolean skipOnTimeout;

        Execution(final StepContext context, final PiplexAwaitStep step) {
            super(context);
            this.key = step.getKey();
            this.generation = step.getGeneration();
            this.timeout = step.getTimeout();
            this.skipOnTimeout = step.isSkipOnTimeout();
        }

        @Override
        public boolean start() throws Exception {
            await();
            return false;
        }

        @Override
        public void onResume() {
            // A waiter holds no position of its own -- it knows which generation it needs and reads the
            // key -- so resuming is the same call as starting. The timeout does start over, which is the
            // one thing a restart changes here.
            try {
                await();
            } catch (final Exception failed) {
                getContext().onFailure(failed);
            }
        }

        private void await() throws Exception {
            final TaskListener listener = getContext().get(TaskListener.class);
            PiplexConfiguration.get().piplexFor(listener).milestones()
                    .awaitAtLeast(key, Generation.of(generation),
                            Durations.parse(timeout, DEFAULT_TIMEOUT, "timeout"))
                    .whenComplete((result, error) -> {
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
