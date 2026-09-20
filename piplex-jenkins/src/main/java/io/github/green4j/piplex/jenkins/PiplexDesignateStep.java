/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import hudson.AbortException;
import hudson.Extension;
import hudson.model.TaskListener;
import io.github.green4j.piplex.exclusive.DesignationChange;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.util.Set;

/**
 * {@code piplexDesignate} -- moves the work to a named owner. Runs only inside
 * {@code withPiplexOperator}.
 *
 * <p>The current holder is revoked, stops and releases. A candidate on the new owner that is parked
 * within its {@code handoverWait} takes over; past it, the work waits for that owner's next
 * scheduled build -- which for nightly work means the round is not done tonight. Designating is
 * therefore a race against a deadline, not a housekeeping write.
 *
 * <p>The environment is the block's. An operator job is copied between controllers, and inheriting
 * whatever the controller happens to default to is how a job meant for one set of orchestrations
 * quietly writes to another.
 */
public final class PiplexDesignateStep extends Step {

    private final String key;
    private final String owner;
    private String reason;
    private boolean overwriteUnreadable;

    /**
     * @param key   the key naming the designated owner, the same one a request names in
     *              {@code designatedBy}
     * @param owner the {@code ownerId} of the controller that should hold the work
     */
    @DataBoundConstructor
    public PiplexDesignateStep(final String key, final String owner) {
        this.key = key;
        this.owner = owner;
    }

    public String getKey() {
        return key;
    }

    public String getOwner() {
        return owner;
    }

    /**
     * @param value why, in the words that would go in a change record or an incident. It is written
     *              into the record and is the only account of the change piplex keeps: the store holds
     *              current state, not history
     */
    @DataBoundSetter
    public void setReason(final String value) {
        this.reason = value;
    }

    public String getReason() {
        return reason;
    }

    /**
     * @param value whether to overwrite a record that does not parse. It changes nothing else: against
     *              a readable record this designates exactly as it otherwise would. Set it only once a
     *              key has been reported unreadable, because a stored value nothing can parse blocks
     *              every ordinary write to that key for good
     */
    @DataBoundSetter
    public void setOverwriteUnreadable(final boolean value) {
        this.overwriteUnreadable = value;
    }

    public boolean isOverwriteUnreadable() {
        return overwriteUnreadable;
    }

    @Override
    public StepExecution start(final StepContext context) {
        return new Execution(context, key, owner, reason, overwriteUnreadable);
    }

    /**
     * Registers the step with Jenkins.
     */
    @Extension
    @Symbol("piplexDesignate")
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "piplexDesignate";
        }

        @Override
        public String getDisplayName() {
            return "Designate the piplex owner of some work";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            // PiplexOperator is what confines this to withPiplexOperator. Asking Jenkins for it is what
            // produces the sentence somebody using it outside needs: the context is missing, and the
            // step which provides it is named.
            return Set.of(PiplexOperator.class, TaskListener.class);
        }
    }

    private static final class Execution extends StepExecution {

        private static final long serialVersionUID = 1L;

        private final String key;
        private final String owner;
        private final String reason;
        private final boolean overwriteUnreadable;

        private transient boolean answered;

        Execution(final StepContext context, final String key, final String owner,
                  final String reason, final boolean overwriteUnreadable) {
            super(context);
            this.key = key;
            this.owner = owner;
            this.reason = reason;
            this.overwriteUnreadable = overwriteUnreadable;
        }

        @Override
        public boolean start() throws Exception {
            final PiplexOperator operator = getContext().get(PiplexOperator.class);
            final TaskListener listener = getContext().get(TaskListener.class);
            // Named apart in the log, because overwriting a value nobody could read is not the same
            // act as designating and the log is where that is afterwards established.
            listener.getLogger().println("piplex: " + (overwriteUnreadable ? "REPAIRING" : "DESIGNATING")
                    + " key=" + key + " owner=" + owner + " as=" + operator.clientId());
            final var designations = operator.piplex().designations();
            (overwriteUnreadable
                    ? designations.repair(key, owner, reason)
                    : designations.designate(key, owner, reason))
                    .whenComplete(this::written);
            return false;
        }

        private void written(final DesignationChange change, final Throwable error) {
            if (!answering()) {
                return;
            }
            if (error != null) {
                getContext().onFailure(error);
                return;
            }
            getContext().onSuccess(change.inForce().owner());
        }

        private synchronized boolean answering() {
            if (answered) {
                return false;
            }
            answered = true;
            return true;
        }

        @Override
        public void stop(final Throwable cause) throws Exception {
            // Through the guard, like the write's own answer: the default would answer the context
            // straight away, and the CAS still in flight would then answer it a second time when it
            // settles. Nothing is waited for -- a designation in flight is left to land or not, and
            // which it did is answered by reading the key, as onResume() below says.
            if (answering()) {
                super.stop(cause);
            }
        }

        @Override
        public void onResume() {
            // Not retried. The identity this was being written under went with the process, and a
            // designation is not idempotent the way a milestone is: asking again moves the record on
            // by another step. Whether the write landed is answered by reading the key.
            if (answering()) {
                getContext().onFailure(new AbortException("piplex: this controller restarted while "
                        + "designating '" + key + "', and the operator identity behind it is not "
                        + "resumed. Read the key to see whether the change landed, then run the job "
                        + "again if it did not"));
            }
        }
    }
}
