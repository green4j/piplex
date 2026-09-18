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
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.util.Set;

/**
 * {@code piplexExclusive} -- runs its body only while this controller holds the requested ownership.
 *
 * <p>It may wrap a whole declarative build in {@code options} or one scripted block. The lease defaults
 * to 60 seconds; {@code handoverWait} defaults to zero. Revocation cancels the body.
 */
public final class PiplexExclusiveStep extends Step {

    private final String key;
    private String environment;
    private String designatedBy;
    private String generation;
    private String completedWhen;
    private String enabledBy;
    private String activeWhenKey;
    private String activeWhenValue;
    private String lease;
    private String renewEvery;
    private String renewalGrace;
    private String guardGrace;
    private String handoverWait;

    /**
     * @param key what is being competed for; controllers competing for the same work name the same key
     */
    @DataBoundConstructor
    public PiplexExclusiveStep(final String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    public String getDesignatedBy() {
        return designatedBy;
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

    /**
     * @param value the key naming the designated owner; leave unset to elect instead
     */
    @DataBoundSetter
    public void setDesignatedBy(final String value) {
        this.designatedBy = value;
    }

    public String getGeneration() {
        return generation;
    }

    /**
     * @param value which round of work this is, usually the business date
     */
    @DataBoundSetter
    public void setGeneration(final String value) {
        this.generation = value;
    }

    public String getCompletedWhen() {
        return completedWhen;
    }

    /**
     * @param value the milestone whose reaching this generation means there is nothing left to do
     */
    @DataBoundSetter
    public void setCompletedWhen(final String value) {
        this.completedWhen = value;
    }

    public String getEnabledBy() {
        return enabledBy;
    }

    /**
     * @param value the switch to consult, and to be stopped by if it is turned off mid-run
     */
    @DataBoundSetter
    public void setEnabledBy(final String value) {
        this.enabledBy = value;
    }

    public String getActiveWhenKey() {
        return activeWhenKey;
    }

    /**
     * @param value an external key, taken as is, which must hold {@code activeWhenValue} for the body
     *              to run, and whose change stops it
     */
    @DataBoundSetter
    public void setActiveWhenKey(final String value) {
        this.activeWhenKey = value;
    }

    public String getActiveWhenValue() {
        return activeWhenValue;
    }

    /**
     * @param value what {@code activeWhenKey} must hold; this controller's owner id when unset
     */
    @DataBoundSetter
    public void setActiveWhenValue(final String value) {
        this.activeWhenValue = value;
    }

    public String getLease() {
        return lease;
    }

    /**
     * @param value how long the lease lasts unless renewed; it bounds how long a handover takes in the
     *              bad case and has nothing to do with how long the work runs
     */
    @DataBoundSetter
    public void setLease(final String value) {
        this.lease = value;
    }

    public String getRenewEvery() {
        return renewEvery;
    }

    /**
     * @param value how often to renew the lease
     */
    @DataBoundSetter
    public void setRenewEvery(final String value) {
        this.renewEvery = value;
    }

    public String getRenewalGrace() {
        return renewalGrace;
    }

    /**
     * @param value how long an unreachable store is tolerated before ownership is given up
     */
    @DataBoundSetter
    public void setRenewalGrace(final String value) {
        this.renewalGrace = value;
    }

    public String getGuardGrace() {
        return guardGrace;
    }

    /**
     * @param value how long a designation or switch which cannot be read at all is tolerated before
     *              ownership is given up
     */
    @DataBoundSetter
    public void setGuardGrace(final String value) {
        this.guardGrace = value;
    }

    public String getHandoverWait() {
        return handoverWait;
    }

    /**
     * @param value how long to wait, rather than give up, when somebody else is designated
     */
    @DataBoundSetter
    public void setHandoverWait(final String value) {
        this.handoverWait = value;
    }

    @Override
    public StepExecution start(final StepContext context) throws Exception {
        return new ExclusiveStepExecution(context, this);
    }

    /**
     * Registers the step with Jenkins.
     */
    @Extension
    @Symbol("piplexExclusive")
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            // Step names are one flat global namespace shared with every other plugin and every shared
            // library in every job. "exclusive" would be a land grab; the prefix is the rent.
            return "piplexExclusive";
        }

        @Override
        public String getDisplayName() {
            return "Run only where piplex allows it";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, TaskListener.class);
        }
    }
}
