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
import io.github.green4j.piplex.Piplex;
import io.github.green4j.piplex.TimeSource;
import io.github.green4j.piplex.switches.SwitchChange;
import io.github.green4j.piplex.switches.Switches;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * {@code piplexSwitch} -- stops work or lets it run again. Runs only inside
 * {@code withPiplexOperator}.
 *
 * <p>Two different operations wear the same name, and they are not the same decision. Without
 * {@code ownerId} it is the shared switch: every owner stops, which is a decision to halt the work
 * itself. With one it drains a single controller, which is maintenance and leaves the work running
 * elsewhere.
 *
 * <p>Draining is not one write. Nothing is safe to patch until what was already running has stopped,
 * so {@code drainTimeout} waits for exactly that -- and waits for it here, on this controller, which
 * is the only place the question can be answered: an admission is held by a process, and no key
 * records which processes are still in one.
 */
public final class PiplexSwitchStep extends Step {

    private static final Duration ROUND = Duration.ofSeconds(1);

    private final String key;
    private final boolean enabled;
    private String reason;
    private String ownerId;
    private String drainTimeout;
    private boolean overwriteUnreadable;

    /**
     * @param key     the switch, as a request names it in {@code enabledBy}
     * @param enabled whether the work it guards may run
     */
    @DataBoundConstructor
    public PiplexSwitchStep(final String key, final boolean enabled) {
        this.key = key;
        this.enabled = enabled;
    }

    public String getKey() {
        return key;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @param value why, in the words that would go in a change record or an incident. Written into the
     *              record, and the only account of the change piplex keeps
     */
    @DataBoundSetter
    public void setReason(final String value) {
        this.reason = value;
    }

    public String getReason() {
        return reason;
    }

    /**
     * @param value the controller to drain, leaving every other owner running. Leave unset to stop the
     *              work everywhere, which is a different decision with a much wider blast radius
     */
    @DataBoundSetter
    public void setOwnerId(final String value) {
        this.ownerId = value;
    }

    public String getOwnerId() {
        return ownerId;
    }

    /**
     * @param value how long to wait for work already running here to stop, such as {@code 30m}. Only
     *              for draining this controller: the wait is local, and claiming it for another
     *              controller would report success having checked nothing
     */
    @DataBoundSetter
    public void setDrainTimeout(final String value) {
        this.drainTimeout = value;
    }

    public String getDrainTimeout() {
        return drainTimeout;
    }

    /**
     * @param value whether to overwrite a record that does not parse. It changes nothing else: against
     *              a readable record this switches exactly as it otherwise would. Set it only once a
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
        return new Execution(context, key, enabled, reason, ownerId, drainTimeout, overwriteUnreadable);
    }

    /**
     * Registers the step with Jenkins.
     */
    @Extension
    @Symbol("piplexSwitch")
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "piplexSwitch";
        }

        @Override
        public String getDisplayName() {
            return "Stop or resume piplex work";
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
        private final boolean enabled;
        private final String reason;
        private final String ownerId;
        private final String drainTimeout;
        private final boolean overwriteUnreadable;

        private transient boolean answered;
        private transient volatile TimeSource.Cancellable ticker;

        Execution(final StepContext context, final String key, final boolean enabled,
                  final String reason, final String ownerId, final String drainTimeout,
                  final boolean overwriteUnreadable) {
            super(context);
            this.key = key;
            this.enabled = enabled;
            this.reason = reason;
            this.ownerId = ownerId;
            this.drainTimeout = drainTimeout;
            this.overwriteUnreadable = overwriteUnreadable;
        }

        @Override
        public boolean start() throws Exception {
            final PiplexOperator operator = getContext().get(PiplexOperator.class);
            final TaskListener listener = getContext().get(TaskListener.class);
            // The key first: an ownerId that names nobody is refused by what it would have written,
            // which says more than the wait's own refusal would.
            final String written = drainedKey();
            final Duration waitFor = drainWait();
            final Piplex piplex = operator.piplex();
            // Named apart in the log, because overwriting a value nobody could read is not the same
            // act as switching and the log is where that is afterwards established.
            listener.getLogger().println("piplex: " + (overwriteUnreadable ? "REPAIRING" : "SWITCHING")
                    + " key=" + written + " enabled=" + enabled + " as=" + operator.clientId());
            final CompletionStage<SwitchChange> writing;
            if (overwriteUnreadable) {
                writing = piplex.switches().repair(written, enabled, reason);
            } else {
                writing = enabled
                        ? piplex.switches().enable(written)
                        : piplex.switches().disable(written, reason);
            }
            final String unconfirmed = waitFor == null ? unconfirmed() : null;
            writing.whenComplete((change, error) ->
                    written(listener, piplex, waitFor, unconfirmed, change, error));
            return false;
        }

        /**
         * The key this step writes: the shared switch, or one deployment's own.
         *
         * <p>An {@code ownerId} that is there but says nobody is refused rather than read as the
         * shared switch. The two are one character apart in a Jenkinsfile and a world apart in the
         * estate: {@code ownerId: params.OWNER} with nothing in {@code OWNER} would take every
         * controller out instead of the one being patched. Leaving the parameter off is how the
         * shared switch is asked for, and that still reads as the deliberate act it is.
         *
         * @return the key to write
         * @throws AbortException if an owner was named as nobody
         */
        private String drainedKey() throws AbortException {
            if (ownerId == null) {
                return key;
            }
            if (ownerId.isBlank()) {
                throw new AbortException("piplex: this step names an ownerId that is empty, and an "
                        + "empty one would write '" + key + "' itself -- the switch that stops this "
                        + "work on every controller, not the one being drained. Name the controller "
                        + "to drain, or leave ownerId off to mean the shared switch on purpose");
            }
            return Switches.ownerKey(key, ownerId.trim());
        }

        /**
         * What a drain that asked for no wait has left unchecked.
         *
         * <p>Writing the switch is not the drain. What makes a controller safe to touch is that the
         * work it was running has stopped, and only {@code drainTimeout} waits for that. Without it
         * this step has changed a record and confirmed nothing -- and a green build is read as an
         * answer. Saying so is the difference between an operator who goes and looks and one who
         * starts patching a controller that is still running the night's work.
         *
         * @return the sentence to log, or {@code null} when nothing is left unsaid
         * @throws AbortException if the configuration is gone
         */
        private String unconfirmed() throws AbortException {
            if (enabled || ownerId == null) {
                return null;
            }
            final String draining = ownerId.trim();
            if (draining.equals(PiplexConfiguration.require().getOwnerId())) {
                return "piplex: NOT CONFIRMED '" + draining + "' takes no new work, and work already "
                        + "running here was not waited for. Set drainTimeout to wait for it, or "
                        + "confirm this controller is quiet before it is touched";
            }
            return "piplex: NOT CONFIRMED '" + draining + "' takes no new work, and nothing here can "
                    + "see what that controller is still running. Run the drain on '" + draining
                    + "' itself, which is where the wait can be answered";
        }

        /**
         * How long to wait for work here to stop, having checked that waiting would mean anything.
         *
         * @return the wait, or {@code null} for none
         * @throws AbortException if a wait was asked for where it could not be answered
         */
        private Duration drainWait() throws AbortException {
            if (drainTimeout == null || drainTimeout.isBlank()) {
                return null;
            }
            if (enabled) {
                throw new AbortException("piplex: drainTimeout waits for work to stop, and this step "
                        + "is letting work run. Set it on the step that disables");
            }
            final String draining = ownerId == null ? null : ownerId.trim();
            final String here = PiplexConfiguration.require().getOwnerId();
            if (draining == null || here == null || !draining.equals(here)) {
                // The wait looks at this controller's own processes. Asked for anyone else it would
                // return at once, having confirmed nothing, and report a controller quiet while it was
                // still working -- which is exactly the answer somebody about to patch it must not get.
                throw new AbortException("piplex: drainTimeout waits for work running on this "
                        + "controller, '" + here + "', and this step drains '" + draining + "'. Run "
                        + "the drain on the controller being taken out, or drop drainTimeout and "
                        + "confirm elsewhere that the work has stopped");
            }
            return Durations.parse(drainTimeout, null, "drainTimeout");
        }

        private void written(final TaskListener listener, final Piplex piplex, final Duration waitFor,
                             final String unconfirmed, final SwitchChange change,
                             final Throwable error) {
            if (error != null) {
                if (answering()) {
                    getContext().onFailure(error);
                }
                return;
            }
            if (waitFor == null) {
                if (unconfirmed != null) {
                    listener.getLogger().println(unconfirmed);
                }
                if (answering()) {
                    getContext().onSuccess(change.inForce().enabled());
                }
                return;
            }
            drain(listener, piplex, waitFor);
        }

        /**
         * Waits for the last admission this switch governs to be let go of here.
         *
         * <p>A round rather than a sleep: what ends the wait is a state, and the state is reached when
         * the guard watch of each admitted run sees the switch and revokes it. How long that takes is
         * the watch period, not something this step can hurry.
         *
         * @param listener the run's log
         * @param piplex   the primitives, for the environment the waiting is done in
         * @param waitFor  how long to wait before saying it has not stopped
         */
        private void drain(final TaskListener listener, final Piplex piplex, final Duration waitFor) {
            final TimeSource time;
            try {
                time = PiplexConfiguration.require().time();
            } catch (final AbortException gone) {
                if (answering()) {
                    getContext().onFailure(gone);
                }
                return;
            }
            final long deadline = time.deadlineIn(waitFor);
            listener.getLogger().println("piplex: DRAINING key=" + key + " owner=" + ownerId
                    + " within=" + waitFor);
            round(listener, time, piplex, deadline);
        }

        private void round(final TaskListener listener, final TimeSource time, final Piplex piplex,
                           final long deadline) {
            if (!ExclusiveStepExecution.anyRunningUnder(key, piplex.environment())) {
                if (answering()) {
                    listener.getLogger().println("piplex: DRAINED key=" + key + " owner=" + ownerId);
                    getContext().onSuccess(Boolean.FALSE);
                }
                return;
            }
            if (time.deadlinePassed(deadline)) {
                if (answering()) {
                    getContext().onFailure(new AbortException("piplex: '" + key + "' is disabled for '"
                            + ownerId + "', but work it guards is still running on this controller "
                            + "after " + drainTimeout + ". It has not stopped; do not take this "
                            + "controller out until it has"));
                }
                return;
            }
            ticker = time.schedule(ROUND, () -> round(listener, time, piplex, deadline));
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
            final TimeSource.Cancellable waiting = ticker;
            if (waiting != null) {
                waiting.cancel();
            }
            if (answering()) {
                super.stop(cause);
            }
        }

        @Override
        public void onResume() {
            // The write may or may not have landed, and the wait counted processes that are gone. Both
            // are answered by looking, which is cheaper than guessing here.
            if (answering()) {
                getContext().onFailure(new AbortException("piplex: this controller restarted while "
                        + "switching '" + key + "'. Read the switch to see whether the change landed, "
                        + "and confirm what is running before taking the controller out"));
            }
        }
    }
}
