/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.domains.Domain;
import hudson.model.Result;
import hudson.util.Secret;
import io.github.green4j.piplex.TimeSource;
import io.github.green4j.piplex.store.memory.InMemoryCoordinationStore;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the operator block has to get right about identity.
 *
 * <p>These are not tests of what operator writes do -- those are the core's. They answer the one
 * question the block exists for: can a build end up writing as somebody it was not entitled to be.
 */
@WithJenkins
class PiplexOperatorBlockTest {

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final TimeSource time = TimeSource.of(scheduler);
    private InMemoryCoordinationStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryCoordinationStore(time);
    }

    /**
     * Puts the real store back.
     *
     * <p>{@code useStore} sets a static, and nothing else clears it. Left set, it is inherited by
     * whichever class JUnit runs next, which then asks a store this one made about keys it never
     * wrote -- a failure that moves when a test class is renamed.
     */
    @AfterEach
    void releaseTheSuppliedStore() {
        PiplexConfiguration.useStore(null, null);
    }

    @Test
    void actsAsTheControllerWhenNoIdentityIsNamed(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");

        final WorkflowRun run = build(jenkins, "ops-default", """
                withPiplexOperator {
                    echo 'operating'
                }
                """);

        jenkins.assertBuildStatus(Result.SUCCESS, run);
        jenkins.assertLogContains("operating", run);
        // Said out loud, because which identity a write was made under is the thing an operator
        // reading this log later needs and cannot reconstruct.
        jenkins.assertLogContains("piplex: OPERATOR as=this controller", run);
    }

    @Test
    void refusesACredentialThisJobCannotRead(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");

        final WorkflowRun run = build(jenkins, "ops-missing", """
                withPiplexOperator(credentialsId: 'not-here', clientId: 'piplex-ops') {
                    echo 'must not run'
                }
                """);

        jenkins.assertBuildStatus(Result.FAILURE, run);
        jenkins.assertLogNotContains("must not run", run);
        jenkins.assertLogContains("no credential 'not-here' is available to this job", run);
    }

    @Test
    void refusesAnIdentityItCannotName(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");
        secretText(jenkins, "ops-token", "s3cret");

        // Nothing in a credential says which name an ACL grants, so guessing one would be guessing
        // which line of the cluster's ACL file this build is trying to use.
        final WorkflowRun run = build(jenkins, "ops-unnamed", """
                withPiplexOperator(credentialsId: 'ops-token') {
                    echo 'must not run'
                }
                """);

        jenkins.assertBuildStatus(Result.FAILURE, run);
        jenkins.assertLogNotContains("must not run", run);
        jenkins.assertLogContains("names a credential but no clientId", run);
    }

    @Test
    void refusesASecondIdentityWhereTheTransportCannotProveOne(final JenkinsRule jenkins)
            throws Exception {
        configure("euc1-blue");
        secretText(jenkins, "ops-token", "s3cret");

        // The controller is configured without TLS. A discas node in allowall reads a client id as a
        // claim, so acting as piplex-ops there would look like separation and be none.
        final WorkflowRun run = build(jenkins, "ops-plaintext", """
                withPiplexOperator(credentialsId: 'ops-token', clientId: 'piplex-ops') {
                    echo 'must not run'
                }
                """);

        jenkins.assertBuildStatus(Result.FAILURE, run);
        jenkins.assertLogNotContains("must not run", run);
        jenkins.assertLogContains("takes a client id as a claim rather than as a proof", run);
    }

    @Test
    void designatesFromInsideTheBlock(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");

        final WorkflowRun run = build(jenkins, "ops-designate", """
                withPiplexOperator {
                    def owner = piplexDesignate key: 'eod-owner', owner: 'euc1-green',
                                                reason: 'INC-4821'
                    echo "designated ${owner}"
                }
                """);

        jenkins.assertBuildStatus(Result.SUCCESS, run);
        jenkins.assertLogContains("designated euc1-green", run);
        jenkins.assertLogContains("piplex: DESIGNATING key=eod-owner owner=euc1-green", run);
    }

    @Test
    void refusesToDesignateOutsideTheBlock(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");

        // The point of the block: an operator write cannot be made without the Jenkinsfile saying so,
        // and there is no ambient identity to fall back to.
        final WorkflowRun run = build(jenkins, "ops-loose", """
                piplexDesignate key: 'eod-owner', owner: 'euc1-green', reason: 'sneaky'
                """);

        jenkins.assertBuildStatus(Result.FAILURE, run);
        jenkins.assertLogContains("withPiplexOperator", run);
    }

    @Test
    void drainsThisControllerWhenNothingIsRunning(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");

        final WorkflowRun run = build(jenkins, "ops-drain", """
                withPiplexOperator {
                    piplexSwitch key: 'eod-switch', enabled: false, ownerId: 'euc1-blue',
                                 reason: 'patching', drainTimeout: '30s'
                }
                """);

        jenkins.assertBuildStatus(Result.SUCCESS, run);
        jenkins.assertLogContains("piplex: SWITCHING key=eod-switch/@euc1-blue enabled=false", run);
        jenkins.assertLogContains("piplex: DRAINED key=eod-switch", run);
    }

    @Test
    void refusesToDrainNobodyRatherThanStoppingEverybody(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");

        // One character apart in a Jenkinsfile, and a world apart in the estate: read as the shared
        // switch, an empty ownerId stops the work on every controller instead of draining the one
        // being patched. It is what `ownerId: params.OWNER` does on the day OWNER is not set.
        final WorkflowRun run = build(jenkins, "ops-drain-nobody", """
                withPiplexOperator {
                    piplexSwitch key: 'eod-switch', enabled: false, ownerId: '', reason: 'patching'
                }
                """);

        jenkins.assertBuildStatus(Result.FAILURE, run);
        jenkins.assertLogContains("names an ownerId that is empty", run);
        jenkins.assertLogNotContains("piplex: SWITCHING", run);
    }

    @Test
    void refusesToActAsSomebodyItCannotProveItIs(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");

        // Without a credential the block acts as the controller, and the client id is dropped. What
        // would be left is a log line naming an identity the writes were not made under -- and that
        // log is the only account anybody has afterwards of who changed the estate.
        final WorkflowRun run = build(jenkins, "ops-claimed-identity", """
                withPiplexOperator(clientId: 'piplex-ops') {
                    piplexDesignate key: 'eod-owner', owner: 'euc1-green', reason: 'INC-1'
                }
                """);

        jenkins.assertBuildStatus(Result.FAILURE, run);
        jenkins.assertLogContains("asks to act as 'piplex-ops' but names no credential", run);
    }

    @Test
    void refusesToClaimAnotherControllerIsDrained(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");

        // The wait counts this controller's own processes. Allowed for euc1-green it would return at
        // once having checked nothing, and tell somebody it was safe to patch a controller that is
        // still working.
        final WorkflowRun run = build(jenkins, "ops-drain-elsewhere", """
                withPiplexOperator {
                    piplexSwitch key: 'eod-switch', enabled: false, ownerId: 'euc1-green',
                                 drainTimeout: '30s'
                }
                """);

        jenkins.assertBuildStatus(Result.FAILURE, run);
        jenkins.assertLogContains("waits for work running on this controller, 'euc1-blue'", run);
    }

    @Test
    void saysWhatADrainWithNoWaitDidNotCheck(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");

        // Draining another owner is allowed -- the switch is that owner's key, and writing it is a
        // real change. What must not happen is a green build being read as "that controller is now
        // quiet", which is the answer somebody about to patch it is actually after.
        final WorkflowRun run = build(jenkins, "ops-drain-unconfirmed", """
                withPiplexOperator {
                    piplexSwitch key: 'eod-switch', enabled: false, ownerId: 'euc1-green',
                                 reason: 'patching'
                }
                """);

        jenkins.assertBuildStatus(Result.SUCCESS, run);
        jenkins.assertLogContains("piplex: NOT CONFIRMED 'euc1-green' takes no new work", run);
        jenkins.assertLogContains("Run the drain on 'euc1-green' itself", run);
    }

    @Test
    void stopsTheWorkEverywhereWithoutAnOwner(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");

        final WorkflowRun run = build(jenkins, "ops-stop-all", """
                withPiplexOperator {
                    piplexSwitch key: 'eod-switch', enabled: false, reason: 'bad upstream data'
                }
                """);

        jenkins.assertBuildStatus(Result.SUCCESS, run);
        // No @owner: the shared switch, which is the decision to halt the work itself.
        jenkins.assertLogContains("piplex: SWITCHING key=eod-switch enabled=false", run);
    }

    @Test
    void saysWhyTheWorkWouldNotRun(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");

        final WorkflowRun run = build(jenkins, "ops-inspect", """
                withPiplexOperator {
                    piplexDesignate key: 'eod-owner', owner: 'euc1-green', reason: 'failover'
                    piplexSwitch key: 'eod-switch', enabled: false, reason: 'bad upstream data'
                }
                def why = piplexInspect key: 'eod', designatedBy: 'eod-owner',
                                       enabledBy: 'eod-switch', generation: '2026-09-14'
                echo "blocked by ${why}"
                """);

        jenkins.assertBuildStatus(Result.SUCCESS, run);
        // Both reasons, in one answer, rather than two records for the reader to compare.
        jenkins.assertLogContains("'euc1-green' is designated, not 'euc1-blue'", run);
        jenkins.assertLogContains("'eod-switch' is disabled ('bad upstream data')", run);
    }

    @Test
    void saysWhereToLookWhenNothingReadableIsStoppingTheWork(final JenkinsRule jenkins)
            throws Exception {
        configure("euc1-blue");

        // The lease cannot be read, so silence here would be the wrong answer: it sends the reader on.
        final WorkflowRun run = build(jenkins, "ops-inspect-clear", """
                piplexInspect key: 'eod', designatedBy: 'eod-owner', enabledBy: 'eod-switch'
                """);

        jenkins.assertBuildStatus(Result.SUCCESS, run);
        jenkins.assertLogContains("nobody is designated for 'eod-owner'", run);
    }

    // The client a named identity connects with is a thread and a set of connections to the cluster,
    // and nothing but the session holds it. A block naming an environment that cannot be one is
    // refused -- and has to be refused before the client exists, or every run of that job leaves one
    // behind and the log never says so.
    @Test
    void makesNoClientForABlockItIsGoingToRefuse(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");
        secretText(jenkins, "ops-token", "s3cret");
        // TLS, because a second identity is refused without it and the client would never be reached.
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setNodes("n1=10.0.0.1:7101");
        configuration.setTls(true);

        final WorkflowRun run = build(jenkins, "ops-bad-environment", """
                withPiplexOperator(credentialsId: 'ops-token', clientId: 'piplex-ops',
                                   environment: 'uat/2') {
                    piplexInspect key: 'eod'
                }
                """);

        jenkins.assertBuildStatus(Result.FAILURE, run);
        jenkins.assertLogContains("must not contain '/'", run);
        // A discas client names its event loop after the identity it connects as, so this asks about
        // the one client this block could have made rather than counting threads in a running Jenkins.
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                        .noneMatch(thread -> "cas-client-piplex-ops".equals(thread.getName())),
                "The refused block left a discas client running");
    }

    private void configure(final String ownerId) {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setOwnerId(ownerId);
        PiplexConfiguration.useStore(store, time);
    }

    private static void secretText(final JenkinsRule jenkins, final String id, final String secret)
            throws Exception {
        SystemCredentialsProvider.getInstance().getDomainCredentialsMap().put(Domain.global(),
                java.util.List.of(new StringCredentialsImpl(
                        CredentialsScope.GLOBAL, id, "for tests", Secret.fromString(secret))));
        jenkins.jenkins.save();
    }

    private WorkflowRun build(final JenkinsRule jenkins, final String name, final String script)
            throws Exception {
        final WorkflowJob job = jenkins.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition(script, true));
        return job.scheduleBuild2(0).get();
    }
}
