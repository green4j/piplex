/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import hudson.AbortException;
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
 * {@code withPiplexOperator} -- makes operator writes inside its body, under a named identity.
 *
 * <p>Operator steps run only inside this block. That is not ceremony: it puts the one thing worth
 * seeing in a Jenkinsfile -- that these writes are being made, and as whom -- at the top of the
 * block rather than in a parameter of each step, and it leaves no way to make one without saying so.
 *
 * <pre>
 * withPiplexOperator(credentialsId: 'piplex-ops-cert', clientId: 'piplex-ops') {
 *     piplexDesignate key: 'eod-owner', owner: 'euc1-green', reason: 'INC-4821'
 * }
 * </pre>
 *
 * <p>With no credential the block acts as this controller, which is what a deployment that has not
 * separated operator writes from controllers wants. With one it acts as the identity the credential
 * carries, and the credential is resolved against the running build -- so who may act as the
 * operations client is decided by which jobs can read it.
 */
public final class PiplexOperatorStep extends Step {

    private String credentialsId;
    private String clientId;
    private String environment;

    @DataBoundConstructor
    public PiplexOperatorStep() {
    }

    /**
     * @param value the Jenkins credential carrying the identity to act as: a Certificate credential
     *              under mTLS, a Secret text credential under token. Leave unset to act as this
     *              controller
     */
    @DataBoundSetter
    public void setCredentialsId(final String value) {
        this.credentialsId = value;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    /**
     * @param value the discas client id these writes are made under, which is the name an ACL grants.
     *              Under mTLS it must be the certificate's CN, because that is what the node
     *              authenticates on
     */
    @DataBoundSetter
    public void setClientId(final String value) {
        this.clientId = value;
    }

    public String getClientId() {
        return clientId;
    }

    /**
     * @param value which set of orchestrations these writes belong to. Name it: an operator job is
     *              copied between controllers, and inheriting the controller's default is how a job
     *              meant for prod quietly writes to uat
     */
    @DataBoundSetter
    public void setEnvironment(final String value) {
        this.environment = value;
    }

    public String getEnvironment() {
        return environment;
    }

    @Override
    public StepExecution start(final StepContext context) throws Exception {
        final boolean named = credentialsId != null && !credentialsId.isBlank();
        if (!named && clientId != null && !clientId.isBlank()) {
            // The other way round, and the more dangerous way: without a credential the block acts as
            // this controller, and a client id with nothing to prove it is quietly dropped. What is
            // left is a log line naming an identity the writes were not made under -- which is the one
            // record anybody has afterwards of who changed the estate.
            throw new AbortException("piplex: this block asks to act as '" + clientId + "' but names "
                    + "no credential, so it would act as this controller and say otherwise. Name the "
                    + "credential that carries that identity, or drop clientId to act as this "
                    + "controller and be logged as it");
        }
        if (named && (clientId == null || clientId.isBlank())) {
            // The identity is not derivable from the credential: under mTLS a node authenticates on
            // the certificate's CN, and under token there is nothing in the secret that names anyone.
            // Written out, it is the same string in the Jenkinsfile and in the ACL file, and a
            // mismatch between them is visible in both.
            throw new AbortException("piplex: this block names a credential but no clientId, and the "
                    + "identity a discas ACL grants cannot be guessed from a credential. Set clientId "
                    + "to the name granted in the cluster's ACL file, which under mTLS is the client "
                    + "certificate's CN");
        }
        return new OperatorStepExecution(context, credentialsId, clientId, environment);
    }

    @Extension
    @Symbol("withPiplexOperator")
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "withPiplexOperator";
        }

        @Override
        public String getDisplayName() {
            return "Make piplex operator changes";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, TaskListener.class);
        }

        @Override
        public Set<? extends Class<?>> getProvidedContext() {
            // Declared so that a step needing it is refused, outside this block, with a message
            // naming the block rather than the class that happened to be missing. The refusal works
            // either way; what this buys is that it reads as instructions.
            return Set.of(PiplexOperator.class);
        }
    }
}
