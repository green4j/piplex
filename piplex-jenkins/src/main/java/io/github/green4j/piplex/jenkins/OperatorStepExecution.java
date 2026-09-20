/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import hudson.AbortException;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.github.green4j.piplex.Piplex;
import io.github.green4j.piplex.jenkins.PiplexConfiguration.OperatorSession;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds one operator identity open for the length of a {@code withPiplexOperator} body.
 *
 * <p>The identity is resolved against the running build rather than read from the global
 * configuration, which is what makes Jenkins the thing that decides who may act as the operations
 * client: a folder-scoped credential does not resolve for a job outside the folder. Nothing about
 * that check lives here -- this only declines to go around it.
 *
 * <p>The session is not serializable and is not registered anywhere a restart survives, so a block
 * interrupted by one ends instead of resuming. That is the right answer for a handful of writes:
 * running the job again is cheap, and a resumed block would be one whose credential was checked
 * before the restart and not after.
 */
final class OperatorStepExecution extends StepExecution {

    private static final long serialVersionUID = 1L;

    /**
     * Sessions open right now, by the id their serializable context carries. Never a cache: an entry
     * is put here by the block that opened it and removed when that block ends, and nothing looks one
     * up by credential.
     */
    private static final Map<String, OperatorSession> LIVE = new ConcurrentHashMap<>();

    private final String credentialsId;
    private final String clientId;
    private final String environment;
    private final String sessionId = UUID.randomUUID().toString();

    OperatorStepExecution(final StepContext context, final String credentialsId,
                          final String clientId, final String environment) {
        super(context);
        this.credentialsId = credentialsId;
        this.clientId = clientId;
        this.environment = environment;
    }

    /**
     * The primitives of an open block.
     *
     * @param id the id its context carries
     * @return the primitives, or {@code null} if the block has ended or did not survive a restart
     */
    static Piplex sessionOf(final String id) {
        final OperatorSession open = LIVE.get(id);
        return open == null ? null : open.piplex();
    }

    @Override
    public boolean start() throws Exception {
        final StepContext context = getContext();
        final Run<?, ?> run = context.get(Run.class);
        final TaskListener listener = context.get(TaskListener.class);
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        if (configuration == null) {
            throw new AbortException("piplex: the plugin is not configured on this controller");
        }
        final StandardCredentials credential = credential(run);
        final OperatorSession session =
                configuration.operatorSessionFor(listener, environment, clientId, credential);
        LIVE.put(sessionId, session);
        listener.getLogger().println("piplex: OPERATOR as=" + actingAs());
        try {
            context.newBodyInvoker()
                    .withCallback(new CloseOnFinish(sessionId))
                    .withContext(new PiplexOperator(sessionId, actingAs()))
                    .start();
        } catch (final RuntimeException couldNotStart) {
            close(sessionId);
            throw couldNotStart;
        }
        return false;
    }

    /**
     * The credential this block acts under, resolved as the build rather than as the system.
     *
     * <p>{@link CredentialsProvider#findCredentialById(String, Class, Run, List)} is the overload that
     * answers for the build: it returns nothing where whoever triggered the run may not use the
     * credential there. Resolving under {@code ACL.SYSTEM} instead would return credentials the job
     * was never entitled to, which is the widening this block exists to make impossible.
     *
     * @param run the build asking
     * @return the credential, or {@code null} where the block named none and acts as the controller
     * @throws AbortException if it was named and does not resolve here
     */
    private StandardCredentials credential(final Run<?, ?> run) throws AbortException {
        if (credentialsId == null || credentialsId.isBlank()) {
            return null;
        }
        final StandardCredentials found = CredentialsProvider.findCredentialById(
                credentialsId.trim(), StandardCredentials.class, run, List.of());
        if (found == null) {
            // One sentence for "no such credential" and for "not yours", deliberately: which of the
            // two it is tells a job that may not use a credential whether it exists, and that is
            // itself worth not answering.
            throw new AbortException("piplex: no credential '" + credentialsId.trim() + "' is "
                    + "available to this job. It must exist in a store this job can read -- a folder "
                    + "credential is visible only to jobs in that folder, which is what decides who "
                    + "may act as the operations client");
        }
        CredentialsProvider.track(run, found);
        return found;
    }

    private String actingAs() {
        return clientId == null || clientId.isBlank() ? "this controller" : clientId.trim();
    }

    @Override
    public void onResume() {
        // The session went with the process. Said plainly rather than letting the body run on and
        // find out at its first write, which would be a write attempted with no identity behind it.
        //
        // Closed first, for the JVM that outlived the controller rather than the one that did not: a
        // restart inside one leaves the old session in LIVE, and its client open, under an id this
        // execution still carries.
        close(sessionId);
        getContext().onFailure(new AbortException("piplex: this controller restarted while an "
                + "operator block was open, and an operator identity is not resumed. Run the job "
                + "again; nothing in the block was made durable by being interrupted"));
    }

    @Override
    public void stop(final Throwable cause) throws Exception {
        close(sessionId);
        super.stop(cause);
    }

    /**
     * Closes every identity this controller still has open, for a controller that is stopping while
     * the JVM is not.
     *
     * <p>The counterpart of {@code ExclusiveStepExecution.abandonAll()}, and needed for the same
     * reason: a servlet container or a test restarting Jenkins keeps the statics, and each of these
     * holds a discas client of its own. The builds are suspended and their blocks are answered on the
     * next start, which is where {@link #onResume()} says so; what must not survive is the connection.
     */
    static void closeAll() {
        for (final String id : LIVE.keySet()) {
            close(id);
        }
    }

    private static void close(final String id) {
        final OperatorSession open = LIVE.remove(id);
        if (open != null) {
            open.close();
        }
    }

    /**
     * Closes the identity when the body ends, however it ended.
     *
     * <p>The client is this block's own, so leaving it open would leak a connection per operator
     * build. Nothing here answers the context: the body's own outcome is the block's outcome.
     */
    private static final class CloseOnFinish extends BodyExecutionCallback {

        private static final long serialVersionUID = 1L;

        private final String sessionId;

        CloseOnFinish(final String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public void onSuccess(final StepContext context, final Object result) {
            close(sessionId);
            context.onSuccess(result);
        }

        @Override
        public void onFailure(final StepContext context, final Throwable cause) {
            close(sessionId);
            context.onFailure(cause);
        }
    }
}
