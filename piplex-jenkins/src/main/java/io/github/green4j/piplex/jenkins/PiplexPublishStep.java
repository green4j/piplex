/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.github.green4j.piplex.Generation;
import io.github.green4j.piplex.milestone.PublishResult;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.util.Set;

/**
 * {@code piplexPublish} -- publishes a completed milestone generation.
 *
 * <p>Call it only after successful work. Publication is monotonic and idempotent, and the step returns
 * the generation in force. When an exclusive request uses {@code completedWhen}, publish while its
 * body still holds the lease.
 */
public final class PiplexPublishStep extends Step {

    private final String key;
    private final String generation;
    private String environment;

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

    /**
     * @param value which set of orchestrations this milestone belongs to; leave unset for the
     *              controller's default, set in Manage Jenkins &gt; System
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
        return new Execution(context, key, generation, environment);
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
        // Written down with the rest: a resumed publish must write in the environment it began in.
        private final String environment;

        // Not carried across a restart: onResume publishes again, and that write is nobody's to stop yet.
        private transient boolean answered;
        // A write sent and not yet settled, and the stop that arrived meanwhile. Guarded by this.
        private transient boolean writing;
        private transient Throwable stoppedWith;

        Execution(final StepContext context,
                  final String key,
                  final String generation,
                  final String environment) {
            super(context);
            this.key = key;
            this.generation = generation;
            this.environment = environment;
        }

        @Override
        public boolean start() throws Exception {
            publish();
            return false;
        }

        /**
         * Ends the step, unless the answer it was waiting for got there first.
         *
         * @param cause why the build is stopping
         */
        @Override
        public void stop(final Throwable cause) throws Exception {
            synchronized (this) {
                if (writing && !answered) {
                    // Answered once the write settles. Answered now, the body ends and its lease goes
                    // back while the milestone is still on its way, and the next owner redoes the work.
                    stoppedWith = cause;
                    return;
                }
            }
            if (answering()) {
                super.stop(cause);
            }
        }

        /**
         * Claims the right to answer the context, once.
         *
         * <p>A watch in discas is a poll bounded by the wait it was given, and there is nothing here
         * that can call one off early -- so the answer still coming and the build being stopped are two
         * outcomes racing for one step. A flag read and then acted on leaves the gap between the two
         * open, which is an aborted build getting a second outcome minutes after somebody pressed the
         * button. Whichever asks first here wins, and the other one does nothing.
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
            // Publishing is idempotent and monotonic, so the answer to "did the write land before the
            // restart" does not have to be known: asking again is correct either way.
            try {
                publish();
            } catch (final Exception failed) {
                if (answering()) {
                    getContext().onFailure(failed);
                }
            }
        }

        private void publish() throws Exception {
            final TaskListener listener = getContext().get(TaskListener.class);
            final Run<?, ?> run = getContext().get(Run.class);
            final PiplexConfiguration.Configured configured =
                    PiplexConfiguration.require().configuredFor(listener, environment);
            synchronized (this) {
                writing = true;
            }
            try {
                configured.piplex().milestones()
                        // The externalizable id, "eod#142", which is what the exclusive step writes and
                        // what an aggregator groups a night's events by. A display name is a pipeline's to
                        // set, so two runs can carry the same one and the grouping quietly merges them.
                        .publish(key, Generation.of(generation), configured.ownerId(),
                                run.getExternalizableId())
                        .whenComplete(this::written);
            } catch (final RuntimeException notSent) {
                synchronized (this) {
                    writing = false;
                }
                throw notSent;
            }
        }

        private void written(final PublishResult result, final Throwable error) {
            final Throwable stopped;
            synchronized (this) {
                writing = false;
                stopped = stoppedWith;
            }
            if (!answering()) {
                return;
            }
            if (stopped != null) {
                getContext().onFailure(stopped);
            } else if (error != null) {
                getContext().onFailure(error);
            } else {
                getContext().onSuccess(result.inForce().generation().value());
            }
        }
    }
}
