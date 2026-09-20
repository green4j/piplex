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
import io.github.green4j.piplex.Piplex;
import io.github.green4j.piplex.UnreadableKeyException;
import io.github.green4j.piplex.exclusive.Designation;
import io.github.green4j.piplex.milestone.Milestone;
import io.github.green4j.piplex.store.Entry;
import io.github.green4j.piplex.switches.Switch;
import io.github.green4j.piplex.switches.Switches;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * {@code piplexInspect} -- says why work would or would not run, in one answer.
 *
 * <p>Answers the question an operator actually has at two in the morning: is tonight's round going to
 * be produced. That answer is not in any one key -- it is the designation, the shared switch, this
 * owner's switch, the milestone and the active key, read together and compared against what the work
 * would ask for. A step handing back one record at a time would leave the job to do that comparison,
 * which is the job the step is for.
 *
 * <p>Takes the same parameters as {@code piplexExclusive}, so a diagnosis is written by copying the
 * request being diagnosed. Needs no operator identity: every controller can read its own guards
 * under every trust profile, and reading grants nobody anything.
 *
 * <p>What it cannot say is who holds the lease: there is no read-only way to ask, the only way to
 * learn the holder being to try to take it. So where the keys explain nothing, this bounds its own
 * answer rather than naming a cause it never established -- a run holding the lease is one of the
 * things left, and the schedule never firing is another.
 *
 * <p>A key that will not parse is the one case it must survive rather than fail on, since that is
 * the case it exists for: every guard is read on its own, and an unreadable one becomes a line and a
 * remedy instead of the end of the diagnosis.
 */
public final class PiplexInspectStep extends Step {

    private final String key;
    private String environment;
    private String ownerId;
    private String designatedBy;
    private String enabledBy;
    private String completedWhen;
    private String generation;
    private String activeWhenKey;
    private String activeWhenValue;

    /**
     * @param key the work being diagnosed, as {@code piplexExclusive} names it
     */
    @DataBoundConstructor
    public PiplexInspectStep(final String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    /**
     * @param value which set of orchestrations to read it in; leave unset for the controller's default
     */
    @DataBoundSetter
    public void setEnvironment(final String value) {
        this.environment = value;
    }

    public String getEnvironment() {
        return environment;
    }

    /**
     * @param value the owner being asked about; leave unset for this controller
     */
    @DataBoundSetter
    public void setOwnerId(final String value) {
        this.ownerId = value;
    }

    public String getOwnerId() {
        return ownerId;
    }

    /**
     * @param value the key naming the designated owner, as the request names it
     */
    @DataBoundSetter
    public void setDesignatedBy(final String value) {
        this.designatedBy = value;
    }

    public String getDesignatedBy() {
        return designatedBy;
    }

    /**
     * @param value the switch, as the request names it
     */
    @DataBoundSetter
    public void setEnabledBy(final String value) {
        this.enabledBy = value;
    }

    public String getEnabledBy() {
        return enabledBy;
    }

    /**
     * @param value the milestone the request would skip for, as it names it
     */
    @DataBoundSetter
    public void setCompletedWhen(final String value) {
        this.completedWhen = value;
    }

    public String getCompletedWhen() {
        return completedWhen;
    }

    /**
     * @param value the round being asked about, usually the business date
     */
    @DataBoundSetter
    public void setGeneration(final String value) {
        this.generation = value;
    }

    public String getGeneration() {
        return generation;
    }

    /**
     * @param value the key an external system writes, as the request names it
     */
    @DataBoundSetter
    public void setActiveWhenKey(final String value) {
        this.activeWhenKey = value;
    }

    public String getActiveWhenKey() {
        return activeWhenKey;
    }

    /**
     * @param value the value of that key which admits this owner
     */
    @DataBoundSetter
    public void setActiveWhenValue(final String value) {
        this.activeWhenValue = value;
    }

    public String getActiveWhenValue() {
        return activeWhenValue;
    }

    @Override
    public StepExecution start(final StepContext context) {
        return new Execution(context, this);
    }

    /**
     * Registers the step with Jenkins.
     */
    @Extension
    @Symbol("piplexInspect")
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "piplexInspect";
        }

        @Override
        public String getDisplayName() {
            return "Say why piplex work would or would not run";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, TaskListener.class);
        }
    }

    /**
     * Reads every guard and states the conclusion.
     *
     * <p>Synchronous: it is a handful of reads made by somebody waiting for the answer, and holding
     * the thread for them is simpler than being resumable for a diagnosis that would be rerun anyway.
     */
    private static final class Execution extends SynchronousNonBlockingStepExecution<String> {

        private static final long serialVersionUID = 1L;
        private static final long READ_SECONDS = 30;

        private final String key;
        private final String environment;
        private final String ownerId;
        private final String designatedBy;
        private final String enabledBy;
        private final String completedWhen;
        private final String generation;
        private final String activeWhenKey;
        private final String activeWhenValue;

        Execution(final StepContext context, final PiplexInspectStep step) {
            super(context);
            this.key = step.key;
            this.environment = step.environment;
            this.ownerId = step.ownerId;
            this.designatedBy = step.designatedBy;
            this.enabledBy = step.enabledBy;
            this.completedWhen = step.completedWhen;
            this.generation = step.generation;
            this.activeWhenKey = step.activeWhenKey;
            this.activeWhenValue = step.activeWhenValue;
        }

        @Override
        protected String run() throws Exception {
            final TaskListener listener = getContext().get(TaskListener.class);
            final PiplexConfiguration configuration = PiplexConfiguration.require();
            final PiplexConfiguration.Configured configured =
                    configuration.configuredFor(listener, environment);
            final Piplex piplex = configured.piplex();
            final String owner = ownerId == null || ownerId.isBlank()
                    ? configured.ownerId()
                    : ownerId.trim();
            final PrintStream log = listener.getLogger();
            log.println("piplex: INSPECT environment=" + piplex.environment().value()
                    + " key=" + key + " owner=" + owner
                    + (generation == null ? "" : " generation=" + generation));

            // Above the guards, because they say how much the guards below are worth: an owner id two
            // controllers answer to makes every line under it true and the conclusion still wrong.
            warnings(log, configuration);

            // Every read started before any of them is waited for. They are independent, and asked
            // one after another each one's timeout is the operator's to sit through: five keys and a
            // store that is slow rather than down used to be two and a half minutes of a pool thread
            // and of somebody's incident. Started together, the wait is the slowest single read.
            final Reads reads = start(piplex, configuration, owner);

            final List<String> blocking = new ArrayList<>();
            designation(log, reads.designation(), owner, blocking);
            switches(log, reads, owner, blocking);
            milestone(log, reads.milestone(), blocking);
            activeKey(log, reads.active(), owner, blocking);

            if (blocking.isEmpty()) {
                // Deliberately not "the lease is held": this step never asked, and there is no
                // read-only way to. Naming one cause it did not establish is how a diagnosis sends
                // somebody to read build logs while the schedule is what never fired.
                log.println("piplex: nothing in these keys is stopping this work. What is left is "
                        + "either a run already holding the lease -- its own build log names "
                        + "ownerId/runId, and the store cannot be asked -- or something that is not "
                        + "piplex at all: the schedule, the queue, an executor, or this controller "
                        + "pointed at another cluster");
            } else {
                log.println("piplex: this work would not run here: " + String.join("; ", blocking));
            }
            return String.join("; ", blocking);
        }

        /**
         * Every key this diagnosis reads, asked for at once.
         *
         * <p>A guard the request does not name is {@code null} here rather than a read of nothing, so
         * each reader below keeps deciding for itself whether it has anything to say.
         *
         * @param designation who is designated
         * @param shared      the switch the work names
         * @param drained     that switch under this owner
         * @param milestone   what has been published
         * @param active      the external key, taken as is
         */
        private record Reads(CompletionStage<Designation> designation,
                             CompletionStage<Switch> shared,
                             CompletionStage<Switch> drained,
                             CompletionStage<Milestone> milestone,
                             CompletionStage<Entry> active) {
        }

        private Reads start(final Piplex piplex, final PiplexConfiguration configuration,
                            final String owner) throws AbortException {
            final boolean switched = enabledBy != null && !enabledBy.isBlank();
            return new Reads(
                    designatedBy == null || designatedBy.isBlank()
                            ? null : piplex.designations().current(designatedBy),
                    switched ? piplex.switches().current(enabledBy) : null,
                    switched ? piplex.switches().current(Switches.ownerKey(enabledBy, owner)) : null,
                    completedWhen == null || completedWhen.isBlank()
                            ? null : piplex.milestones().current(completedWhen),
                    activeWhenKey == null || activeWhenKey.isBlank()
                            ? null : configuration.store().get(activeWhenKey.trim()));
        }

        /**
         * The two things this controller knows about itself that change what the reading below means.
         *
         * <p>Both are held in memory by the heartbeat and cost no read. Neither is added to the
         * blocking list: a duplicated owner id does not stop the work, and counted as a blocker it
         * would make a successful handover report itself as failed. Loudness comes from the result
         * instead -- a yellow ball is seen in a list of builds, where a line in a log is not.
         *
         * @param log           where the diagnosis is written
         * @param configuration the controller's own configuration
         * @throws Exception if the build cannot be reached to mark it
         */
        private void warnings(final PrintStream log, final PiplexConfiguration configuration)
                throws Exception {
            final String duplicated = configuration.duplicatedOwner();
            final String silent = configuration.silentOwner();
            if (duplicated != null) {
                log.println("piplex: WARNING another live controller is using owner id '"
                        + duplicated + "'. A designation naming it names both of them, and which one "
                        + "runs the work is left to chance");
            }
            if (silent != null) {
                log.println("piplex: WARNING the heartbeat for '" + silent + "' has not been written "
                        + "for a while, so the duplicate-owner check is not running and this "
                        + "diagnosis cannot rule one out. An ACL without 'instances/' is the usual "
                        + "reason");
            }
            if (duplicated != null || silent != null) {
                getContext().get(Run.class).setResult(Result.UNSTABLE);
            }
        }

        private void designation(final PrintStream log, final CompletionStage<Designation> asked,
                                 final String owner, final List<String> blocking) throws Exception {
            if (asked == null) {
                return;
            }
            final Designation current;
            try {
                current = read(asked);
            } catch (final Exception failure) {
                unreadable(log, failure, "designated/" + designatedBy, blocking,
                        "run 'handover' with DESIGNATION_KEY=" + designatedBy
                                + ", NEW_OWNER=<the owner that should hold it> and "
                                + "OVERWRITE_UNREADABLE=true");
                return;
            }
            if (current == null) {
                log.println("piplex:   designated/" + designatedBy + " = nobody");
                blocking.add("nobody is designated for '" + designatedBy + "'");
                return;
            }
            log.println("piplex:   designated/" + designatedBy + " = " + current.owner()
                    + reasonOf(current.reason()));
            if (!owner.equals(current.owner())) {
                blocking.add("'" + current.owner() + "' is designated, not '" + owner + "'");
            }
        }

        private void switches(final PrintStream log, final Reads reads, final String owner,
                              final List<String> blocking) throws Exception {
            if (reads.shared() == null) {
                return;
            }
            try {
                final Switch shared = read(reads.shared());
                log.println("piplex:   enabled/" + enabledBy + " = " + state(shared));
                if (!shared.enabled()) {
                    blocking.add("'" + enabledBy + "' is disabled" + reasonOf(shared.reason()));
                }
            } catch (final Exception failure) {
                unreadable(log, failure, "enabled/" + enabledBy, blocking,
                        "run 'stop-work' with SWITCH_KEY=" + enabledBy
                                + ", ACTION=<stop or resume> and OVERWRITE_UNREADABLE=true");
            }
            // Read apart from the shared one: either can be corrupt on its own, and a diagnosis that
            // gave up on the first would leave the drain of this very controller unreported.
            final String ownerKey = Switches.ownerKey(enabledBy, owner);
            try {
                final Switch drained = read(reads.drained());
                log.println("piplex:   enabled/" + ownerKey + " = " + state(drained));
                if (!drained.enabled()) {
                    blocking.add("'" + owner + "' is drained" + reasonOf(drained.reason()));
                }
            } catch (final Exception failure) {
                unreadable(log, failure, "enabled/" + ownerKey, blocking,
                        "run 'drain-controller' on '" + owner + "' with SWITCH_KEY=" + enabledBy
                                + ", OWNER_ID=" + owner + ", ACTION=<drain or restore> and "
                                + "OVERWRITE_UNREADABLE=true");
            }
        }

        private void milestone(final PrintStream log, final CompletionStage<Milestone> asked,
                               final List<String> blocking) throws Exception {
            if (asked == null) {
                return;
            }
            final Milestone current;
            try {
                current = read(asked);
            } catch (final Exception failure) {
                unreadable(log, failure, "milestone/" + completedWhen, blocking,
                        "run 'announce-completion' on the controller that produces it, with "
                                + "MILESTONE_KEY=" + completedWhen + ", GENERATION=<the round that "
                                + "is really complete> and OVERWRITE_UNREADABLE=true");
                return;
            }
            if (current == null) {
                log.println("piplex:   milestone/" + completedWhen + " = nothing published");
                return;
            }
            log.println("piplex:   milestone/" + completedWhen + " = " + current.generation().value());
            if (generation != null && !generation.isBlank()
                    && current.generation().compareTo(Generation.of(generation)) >= 0) {
                // Not a fault: the work is not running because it is done, which is the answer somebody
                // asking at two in the morning most wants to be given plainly.
                blocking.add("'" + generation + "' is already complete at '"
                        + current.generation().value() + "'");
            }
        }

        private void activeKey(final PrintStream log, final CompletionStage<Entry> asked,
                               final String owner, final List<String> blocking) throws Exception {
            if (asked == null) {
                return;
            }
            final String named = activeWhenKey.trim();
            final Entry entry = read(asked);
            final String value = entry.exists() ? entry.value() : null;
            log.println("piplex:   " + named + " = " + (value == null ? "unset" : value));
            // The default piplexExclusive applies, applied here for the same reason it is there: one
            // Jenkinsfile for every site, each controller waiting for its own name. A request copied
            // into this step without the value is the ordinary case, and reading it as "no guard at
            // all" is how this step comes to report work runnable that piplexExclusive is refusing.
            final String admits = activeWhenValue == null || activeWhenValue.isBlank()
                    ? owner
                    : activeWhenValue.trim();
            if (!admits.equals(value)) {
                // Named apart from the rest: this key belongs to whatever system does the failover, so
                // the fix is over there and not in piplex.
                blocking.add("'" + named + "' is '" + value + "', and this work runs at '"
                        + admits + "' -- the system that writes that key decides it");
            }
        }

        /**
         * Reports a key holding something that will not parse, and says what puts it right.
         *
         * <p>The case this whole step exists for, so it is the one case it must survive. A value
         * nothing can parse fails every ordinary write to that key as well as every read, so the work
         * it guards is stopped until somebody overwrites it -- and which job does that is knowable
         * from the key, while what to overwrite it with never is. Both halves are said here.
         *
         * <p>Anything else is rethrown. A store that cannot be reached is not a diagnosis.
         *
         * @param log      where the diagnosis is written
         * @param failure  what the read threw
         * @param shown    the key as this step names it
         * @param blocking what is stopping the work, added to
         * @param fix      the job and parameters that put this key right
         * @throws Exception the original failure, when it is not an unreadable key
         */
        private static void unreadable(final PrintStream log, final Exception failure,
                                       final String shown, final List<String> blocking,
                                       final String fix) throws Exception {
            final UnreadableKeyException notReadable = unreadableCause(failure);
            if (notReadable == null) {
                throw failure;
            }
            log.println("piplex:   " + shown + " = UNREADABLE");
            log.println("piplex:     " + notReadable.getMessage());
            log.println("piplex:     fix: " + fix);
            blocking.add("'" + shown + "' cannot be read, so nothing may run under it");
        }

        /**
         * @param failure what a read threw, wrapped in however many layers the futures added
         * @return the unreadable key inside it, or {@code null} when it is a different failure
         */
        private static UnreadableKeyException unreadableCause(final Throwable failure) {
            // Bounded rather than walked to the end: a cause chain that points back at itself would
            // hang the diagnosis, and nothing wraps a key this deeply.
            Throwable cause = failure;
            for (int depth = 0; cause != null && depth < 8; depth++) {
                if (cause instanceof UnreadableKeyException notReadable) {
                    return notReadable;
                }
                cause = cause.getCause();
            }
            return null;
        }

        private static <T> T read(final CompletionStage<T> stage) throws Exception {
            return stage.toCompletableFuture().get(READ_SECONDS, TimeUnit.SECONDS);
        }

        private static String state(final Switch value) {
            return value.enabled() ? "enabled" : "disabled" + reasonOf(value.reason());
        }

        private static String reasonOf(final String reason) {
            return reason == null || reason.isBlank() ? "" : " ('" + reason + "')";
        }
    }
}
