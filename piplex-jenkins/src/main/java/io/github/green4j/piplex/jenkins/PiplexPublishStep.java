/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.github.green4j.piplex.Generation;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.Set;

/**
 * {@code piplexPublish} -- say how far the work got, so that pipelines elsewhere can start.
 *
 * <pre>
 * post { success { piplexPublish key: 'data/euc1', generation: env.BUSINESS_DATE } }
 * </pre>
 *
 * <p>In {@code post { success { } }} and nowhere else. A milestone is a promise that the data is there,
 * and a producer that publishes on the way out regardless of outcome turns every waiting pipeline into a
 * consumer of half-written days. Publishing only on success is what makes an interrupted producer safe:
 * it publishes nothing, and the waiters keep waiting.
 */
public final class PiplexPublishStep extends Step {

    private final String key;
    private final String generation;

    /**
     * @param key        the milestone
     * @param generation how far the producer got, usually the business date
     */
    @DataBoundConstructor
    public PiplexPublishStep(final String key, final String generation) {
        this.key = key;
        this.generation = generation;
    }

    public String getKey() {
        return key;
    }

    public String getGeneration() {
        return generation;
    }

    @Override
    public StepExecution start(final StepContext context) {
        return new Execution(context, key, generation);
    }

    /**
     * Registers the step with Jenkins.
     */
    @Extension
    @Symbol("piplexPublish")
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "piplexPublish";
        }

        @Override
        @NonNull
        public String getDisplayName() {
            return "Publish a piplex milestone";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, TaskListener.class);
        }
    }

    private static final class Execution extends StepExecution {

        private static final long serialVersionUID = 1L;

        private final String key;
        private final String generation;

        Execution(final StepContext context, final String key, final String generation) {
            super(context);
            this.key = key;
            this.generation = generation;
        }

        @Override
        public boolean start() throws Exception {
            publish();
            return false;
        }

        @Override
        public void onResume() {
            // Publishing is idempotent and monotonic, so the answer to "did the write land before the
            // restart" does not have to be known: asking again is correct either way.
            try {
                publish();
            } catch (final Exception failed) {
                getContext().onFailure(failed);
            }
        }

        private void publish() throws Exception {
            final PiplexConfiguration configuration = PiplexConfiguration.get();
            final TaskListener listener = getContext().get(TaskListener.class);
            final Run<?, ?> run = getContext().get(Run.class);
            configuration.piplexFor(listener).milestones()
                    .publish(key, Generation.of(generation), configuration.requireOwnerId(),
                            run.getFullDisplayName())
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            getContext().onFailure(error);
                        } else {
                            getContext().onSuccess(result.inForce().generation().value());
                        }
                    });
        }
    }
}
