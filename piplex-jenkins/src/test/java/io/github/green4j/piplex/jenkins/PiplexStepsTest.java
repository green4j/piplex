/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import hudson.AbortException;
import hudson.ExtensionList;
import hudson.model.Result;
import hudson.model.TaskListener;
import io.github.green4j.piplex.Generation;
import io.github.green4j.piplex.TimeSource;
import io.github.green4j.piplex.exclusive.Admission;
import io.github.green4j.piplex.exclusive.Admitted;
import io.github.green4j.piplex.exclusive.Designations;
import io.github.green4j.piplex.exclusive.ExclusiveRequest;
import io.github.green4j.piplex.exclusive.ExclusiveRuns;
import io.github.green4j.piplex.milestone.Milestones;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.Entry;
import io.github.green4j.piplex.store.LeaseAttempt;
import io.github.green4j.piplex.store.LeaseHandle;
import io.github.green4j.piplex.store.memory.InMemoryCoordinationStore;
import io.github.green4j.piplex.switches.Switches;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.DataBoundConstructor;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the adapter has to get right, and what only a real controller can answer.
 *
 * <p>These are not tests of the decisions -- those are made in the core and tested there in
 * milliseconds against a fake store. These answer the questions that only Jenkins can: does a build that
 * was not the one to run end as {@code NOT_BUILT} rather than red, does the body actually stop when
 * ownership is taken away, and does an hour of waiting cost an executor.
 */
@WithJenkins
class PiplexStepsTest {

    private static final String ACTIVE = "/dc/active";

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final TimeSource time = TimeSource.of(scheduler);
    private InMemoryCoordinationStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryCoordinationStore(time);
    }

    @Test
    void runsTheBodyOnTheDesignatedController(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");
        designate("euc1-blue");

        final WorkflowRun run = build(jenkins, "eod", """
                node {
                    piplexExclusive(key: 'eod', designatedBy: 'eod', generation: '2026-09-12') {
                        echo 'end of day ran here'
                    }
                }
                """);

        jenkins.assertBuildStatus(Result.SUCCESS, run);
        jenkins.assertLogContains("end of day ran here", run);
        jenkins.assertLogContains("piplex: ADMITTED key=eod", run);
    }

    @Test
    void returnsNothingFromTheBlock(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");

        // After a restart the value is gone, so it is never returned: the answer must not depend on one.
        final WorkflowRun run = build(jenkins, "eod-value", """
                def value = piplexExclusive(key: 'eod') { 42 }
                echo "block returned ${value}"
                """);

        jenkins.assertBuildStatus(Result.SUCCESS, run);
        jenkins.assertLogContains("block returned null", run);
    }

    @Test
    void doesNotFailTheBuildThatWasNotTheOneToRun(final JenkinsRule jenkins) throws Exception {
        configure("eus1-blue");
        designate("euc1-blue");

        final WorkflowRun run = build(jenkins, "eod-elsewhere", """
                node {
                    piplexExclusive(key: 'eod', designatedBy: 'eod', generation: '2026-09-12') {
                        echo 'this must not run'
                    }
                }
                """);

        // The whole point: three controllers a night doing exactly what they were told must not paint
        // the build page red, or the build page stops meaning anything.
        jenkins.assertBuildStatus(Result.NOT_BUILT, run);
        jenkins.assertLogNotContains("this must not run", run);
    }

    @Test
    void stopsTheBuildWhenTheDesignationMovesEvenIfTheBodyThenEndsWell(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");
        designate("euc1-blue");
        ExtensionList.lookup(StepDescriptor.class).add(new EndsWellWhenStopped.DescriptorImpl());

        final WorkflowJob job = jenkins.createProject(WorkflowJob.class, "eod-late-stop");
        job.setDefinition(new CpsFlowDefinition("""
                piplexExclusive(key: 'eod', designatedBy: 'eod', generation: '2026-09-12') {
                    endsWellWhenStopped()
                }
                """, true));
        final WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        jenkins.waitForMessage("working", run);

        // The body ends well once told to stop: a cancel that lands after the work is already done.
        designate("eus1-blue");

        jenkins.waitForCompletion(run);
        jenkins.assertBuildStatus(Result.ABORTED, run);
        jenkins.assertLogContains("DESIGNATION_CHANGED", run);
    }

    /**
     * A step which finishes successfully when stopped.
     */
    public static final class EndsWellWhenStopped extends Step {

        @DataBoundConstructor
        public EndsWellWhenStopped() {
        }

        @Override
        public StepExecution start(final StepContext context) {
            return new Execution(context);
        }

        private static final class Execution extends StepExecution {

            private static final long serialVersionUID = 1L;

            Execution(final StepContext context) {
                super(context);
            }

            @Override
            public boolean start() throws Exception {
                getContext().get(TaskListener.class).getLogger().println("working");
                return false;
            }

            @Override
            public void stop(final Throwable cause) {
                getContext().onSuccess(null);
            }
        }

        public static final class DescriptorImpl extends StepDescriptor {

            @Override
            public String getFunctionName() {
                return "endsWellWhenStopped";
            }

            @Override
            public Set<? extends Class<?>> getRequiredContext() {
                return Set.of(TaskListener.class);
            }
        }
    }

    @Test
    void keepsTheLeaseUntilTheStoppedBodyHasActuallyEnded(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");
        designate("euc1-blue");
        ExtensionList.lookup(StepDescriptor.class).add(new SlowToStop.DescriptorImpl());
        SlowToStop.reset();

        final WorkflowJob job = jenkins.createProject(WorkflowJob.class, "eod-slow-stop");
        job.setDefinition(new CpsFlowDefinition("""
                piplexExclusive(key: 'eod', designatedBy: 'eod', generation: '2026-09-12') {
                    slowToStop()
                }
                """, true));
        final WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        jenkins.waitForMessage("working", run);

        designate("eus1-blue");
        SlowToStop.stopAsked.get(30L, TimeUnit.SECONDS);

        // Told to stop, and not stopped yet: the work may still be touching what the lease guards.
        final Admission meanwhile = new ExclusiveRuns(store, time).begin(ExclusiveRequest.builder("eod")
                        .ownedBy("eus1-blue")
                        .runId("next#1")
                        .build())
                .toCompletableFuture().get(10L, TimeUnit.SECONDS);
        assertInstanceOf(Admission.HeldByOther.class, meanwhile,
                "The lease must stand until the body has actually ended");

        SlowToStop.letGo.complete(null);
        jenkins.waitForCompletion(run);
        jenkins.assertBuildStatus(Result.ABORTED, run);
        assertTrue(leaseIsFree(), "Once the body has ended, the lease goes back");
    }

    /**
     * A step which, told to stop, ends only when the test lets it.
     */
    public static final class SlowToStop extends Step {

        static volatile CompletableFuture<Void> stopAsked = new CompletableFuture<>();
        static volatile CompletableFuture<Void> letGo = new CompletableFuture<>();

        @DataBoundConstructor
        public SlowToStop() {
        }

        static void reset() {
            stopAsked = new CompletableFuture<>();
            letGo = new CompletableFuture<>();
        }

        @Override
        public StepExecution start(final StepContext context) {
            return new Execution(context);
        }

        private static final class Execution extends StepExecution {

            private static final long serialVersionUID = 1L;

            Execution(final StepContext context) {
                super(context);
            }

            @Override
            public boolean start() throws Exception {
                getContext().get(TaskListener.class).getLogger().println("working");
                return false;
            }

            @Override
            public void stop(final Throwable cause) {
                stopAsked.complete(null);
                letGo.whenComplete((ignored, never) -> getContext().onFailure(cause));
            }
        }

        public static final class DescriptorImpl extends StepDescriptor {

            @Override
            public String getFunctionName() {
                return "slowToStop";
            }

            @Override
            public Set<? extends Class<?>> getRequiredContext() {
                return Set.of(TaskListener.class);
            }
        }
    }

    @Test
    void stopsTheBuildWhenOnlyThisControllerIsDrained(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");

        final WorkflowJob job = jenkins.createProject(WorkflowJob.class, "eod-drained-here");
        job.setDefinition(new CpsFlowDefinition("""
                node {
                    piplexExclusive(key: 'eod', enabledBy: 'eod', lease: '5s') {
                        echo 'working'
                        sleep 120
                    }
                }
                """, true));
        final WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        jenkins.waitForMessage("working", run);

        disable(Switches.ownerKey("eod", "euc1-blue"), "patching euc1-blue");

        jenkins.waitForCompletion(run);
        jenkins.assertBuildStatus(Result.ABORTED, run);
        jenkins.assertLogContains("patching euc1-blue", run);
    }

    @Test
    void runsTheBodyWhereTheActiveKeyNamesThisControllerAndStopsItWhenTheKeyMoves(final JenkinsRule jenkins)
            throws Exception {
        configure("euc1-blue");
        activate("euc1-blue");

        final WorkflowJob job = jenkins.createProject(WorkflowJob.class, "eod-active");
        // No value: this controller's own owner id is what the key has to hold.
        job.setDefinition(new CpsFlowDefinition("""
                node {
                    piplexExclusive(key: 'eod', activeWhenKey: '/dc/active', lease: '5s') {
                        echo 'working'
                        sleep 120
                    }
                }
                """, true));
        final WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        jenkins.waitForMessage("working", run);

        activate("euc2-blue");

        jenkins.waitForCompletion(run);
        jenkins.assertBuildStatus(Result.ABORTED, run);
        jenkins.assertLogContains("DEACTIVATED", run);
        jenkins.assertLogContains("'/dc/active' is now 'euc2-blue'", run);
    }

    @Test
    void doesNotRunTheBodyWhereTheActiveKeyNamesAnotherValue(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");
        activate("euc1-blue");

        final WorkflowRun run = build(jenkins, "eod-inactive", """
                piplexExclusive(key: 'eod', activeWhenKey: '/dc/active', activeWhenValue: 'euc1-green') {
                    echo 'this must not run'
                }
                """);

        jenkins.assertBuildStatus(Result.NOT_BUILT, run);
        jenkins.assertLogNotContains("this must not run", run);
        jenkins.assertLogContains("'/dc/active' is 'euc1-blue'", run);
    }

    @Test
    void doesNotRunTheBodyWhenOwnershipIsGoneBeforeItCouldStart(final JenkinsRule jenkins)
            throws Exception {
        designate("euc1-blue");
        // The designation moves after the lease is taken and re-read, before the admission is handed back, so
        // the revocation lands before the step has a body to stop. Microseconds wide in production, and
        // the cost of getting it wrong is the guarded work running on a controller already told not to.
        PiplexConfiguration.get().setOwnerId("euc1-blue");
        PiplexConfiguration.useStore(new DesignationMovesMidAcquire(store, time), time);

        final WorkflowRun run = build(jenkins, "eod-lost-at-the-door", """
                node {
                    piplexExclusive(key: 'eod', designatedBy: 'eod', generation: '2026-09-12') {
                        echo 'this must not run'
                    }
                }
                """);

        jenkins.assertLogNotContains("this must not run", run);
        jenkins.assertBuildStatus(Result.ABORTED, run);
    }

    @Test
    void leavesNoLeaseBehindWhenTheBuildIsCancelledWhileItIsStillAsking(final JenkinsRule jenkins)
            throws Exception {
        designate("euc1-blue");
        PiplexConfiguration.get().setOwnerId("euc1-blue");
        final HeldUpAcquire slow = new HeldUpAcquire(store);
        PiplexConfiguration.useStore(slow, time);

        final WorkflowJob job = jenkins.createProject(WorkflowJob.class, "eod-cancelled-at-the-door");
        job.setDefinition(new CpsFlowDefinition("""
                piplexExclusive(key: 'eod', designatedBy: 'eod', generation: '2026-09-12',
                                lease: '3s') {
                    echo 'this must not run'
                }
                """, true));
        final WorkflowRun run = job.scheduleBuild2(0).waitForStart();

        // Somebody presses the button while the attempt is still in flight: there is no body to cancel
        // and nothing held, so the step ends there -- and the lease it asked for arrives afterwards.
        slow.asked().get(30L, TimeUnit.SECONDS);
        run.doStop();
        slow.answer();

        jenkins.waitForCompletion(run);
        jenkins.assertBuildStatus(Result.ABORTED, run);
        jenkins.assertLogNotContains("this must not run", run);

        // Nothing renews an admission nobody can use, so the key is free again within the lease. It is
        // not given back at once: a retry under the same identity may already hold it.
        assertTrue(leaseIsFree(), "The lease of a build that never started must not be left standing");
    }

    @Test
    void handsTheGuardedWorkTheFencingTokenItWasAdmittedUnder(final JenkinsRule jenkins)
            throws Exception {
        configure("euc1-blue");

        // The token is the only thing that closes the overlap between a lease lapsing and its former
        // holder noticing, and it closes it only where the protected resource is given it. A pipeline
        // which cannot get at the number cannot pass it on, so the guarantee was unreachable from the
        // one place the work is actually written.
        final WorkflowRun run = build(jenkins, "eod-fenced", """
                node {
                    piplexExclusive(key: 'eod', generation: '2026-09-12') {
                        echo "fenced with ${env.PIPLEX_FENCING_TOKEN} and ${piplexToken()}"
                    }
                }
                """);

        jenkins.assertBuildStatus(Result.SUCCESS, run);
        jenkins.assertLogContains("fencingToken=1", run);
        // The same number both ways: the variable a shell command can read, and the step for a body
        // that needs to ask again rather than trust what it was started with.
        jenkins.assertLogContains("fenced with 1 and 1", run);
    }

    @Test
    void refusesToAnswerForATokenOnceOwnershipIsGone(final JenkinsRule jenkins) {
        // A body outlives its admission by however long Jenkins takes to stop it, and a step asking in
        // that window must not be handed the number the block started with: whatever it was about to
        // fence with it is precisely what must not happen now.
        final PiplexOwnership gone = new PiplexOwnership("eod", "eod-gone #1/5");

        final AbortException refused = assertThrows(AbortException.class, gone::fencingToken);

        assertTrue(refused.getMessage().contains("not held here any more"), refused.getMessage());
    }

    @Test
    void refusesToAnswerForATokenOutsideAnyBlock(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");

        // There is no ownership here, so there is no token. Answering anything -- a zero, an empty
        // string -- would be a number going to something that fences on it, from a build that holds
        // nothing at all.
        final WorkflowRun run = build(jenkins, "eod-unfenced", """
                node {
                    echo "the token is ${piplexToken()}"
                }
                """);

        jenkins.assertBuildStatus(Result.FAILURE, run);
        jenkins.assertLogContains("PiplexOwnership", run);
    }

    @Test
    void saysNobodyHoldsTheKeyWhenTakingItLostARace(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");
        PiplexConfiguration.useStore((CoordinationStore) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {CoordinationStore.class},
                (proxy, method, args) -> method.getName().equals("tryAcquire")
                        ? CompletableFuture.completedFuture(new LeaseAttempt.Contended())
                        : method.invoke(store, args)), time);

        final WorkflowRun run = build(jenkins, "eod-contended", """
                piplexExclusive(key: 'eod', generation: '2026-09-12') {
                    echo 'this must not run'
                }
                """);

        jenkins.assertBuildStatus(Result.NOT_BUILT, run);
        jenkins.assertLogContains("piplex: CONTENDED key=eod", run);
        jenkins.assertLogContains("nobody is holding it", run);
        jenkins.assertLogNotContains("'null'", run);
    }

    @Test
    void doesNotLetACloneOfThisControllerShareTheLease(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");

        final WorkflowJob job = jenkins.createProject(WorkflowJob.class, "eod-cloned");
        job.setDefinition(new CpsFlowDefinition("""
                piplexExclusive(key: 'eod', generation: '2026-09-12') {
                    echo 'working'
                    sleep 120
                }
                """, true));
        final WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        jenkins.waitForMessage("working", run);

        // A clone of this JENKINS_HOME runs the same job with the same owner id, the same build number
        // and the same flow graph. Whichever flow node the block is, the clone must not be told it
        // already holds the lease.
        final ExclusiveRuns clone = new ExclusiveRuns(store, time);
        for (int node = 1; node <= 20; node++) {
            final Admission asked = clone.begin(ExclusiveRequest.builder("eod")
                            .ownedBy("euc1-blue")
                            .runId(run.getExternalizableId())
                            .executionId(Integer.toString(node))
                            .build())
                    .toCompletableFuture().get(10L, TimeUnit.SECONDS);
            assertInstanceOf(Admission.HeldByOther.class, asked, "Flow node " + node);
        }

        run.doStop();
        jenkins.waitForCompletion(run);
    }

    @Test
    void warnsWhenAnotherControllerUsesTheSameOwnerId(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.startHeartbeat();
        configuration.beatNow().toCompletableFuture().get(10L, TimeUnit.SECONDS);

        // A clone writes its own mark under the same owner id.
        final String key = OwnerHeartbeat.keyOf("euc1-blue");
        final Entry mine = store.get(key).toCompletableFuture().get(10L, TimeUnit.SECONDS);
        assertTrue(store.compareAndSet(key, mine.version(), "a-clone")
                .toCompletableFuture().get(10L, TimeUnit.SECONDS));
        configuration.beatNow().toCompletableFuture().get(10L, TimeUnit.SECONDS);

        assertTrue(ExtensionList.lookupSingleton(DuplicateOwnerMonitor.class).isActivated(),
                "An administrator has to be told");
        try (JenkinsRule.WebClient web = jenkins.createWebClient()) {
            assertTrue(web.goTo("manage").asNormalizedText()
                            .contains("another live controller uses the owner id 'euc1-blue'"),
                    "And told where an administrator looks");
        }
        final WorkflowRun run = build(jenkins, "eod-duplicated", """
                piplexExclusive(key: 'eod') {
                    echo 'ran'
                }
                """);
        jenkins.assertBuildStatus(Result.SUCCESS, run);
        jenkins.assertLogContains("another live controller uses the owner id 'euc1-blue'", run);
    }

    @Test
    void doesNotLetOneBuildHoldTheSameKeyTwiceAtOnce(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");

        // Two asks by one build. The identity the lease is taken under names the controller and the
        // build, and both asks come from the same build -- so without anything to tell them apart the
        // store says "you already hold this" to the second one, lets it in, and the first of the two
        // blocks to finish gives the lease away while the other is still working under it. Which is
        // what this build would show as the outer block being revoked half way.
        final WorkflowRun run = build(jenkins, "eod-twice", """
                node {
                    piplexExclusive(key: 'eod', generation: '2026-09-12', lease: '5s') {
                        echo 'the first block holds it'
                        catchError(buildResult: 'SUCCESS', stageResult: 'NOT_BUILT',
                                   catchInterruptions: true) {
                            piplexExclusive(key: 'eod', generation: '2026-09-12', lease: '5s') {
                                echo 'this must not run'
                            }
                        }
                        sleep 8
                        echo 'and it still holds it'
                    }
                }
                """);

        jenkins.assertLogNotContains("this must not run", run);
        // Contention, and named as such: what holds the key is another block of this same build.
        jenkins.assertLogContains("heldBy=euc1-blue/eod-twice#1/", run);
        // And the block which does hold it ran to the end and gave the lease back itself, rather than
        // being revoked half way through by the other one letting go of a lease they were sharing.
        jenkins.assertLogContains("and it still holds it", run);
        jenkins.assertLogContains("piplex: RELEASED key=eod", run);
        jenkins.assertLogNotContains("piplex: REVOKED", run);
    }

    @Test
    void guardsAWholeDeclarativeBuildFromOptions(final JenkinsRule jenkins) throws Exception {
        configure("eus1-blue");
        designate("euc1-blue");

        // The shape the euroctp templates would use, and the reason the step takes a block at all: in
        // options{} the block is the whole build, so a controller that is not the one to run never
        // reaches a stage -- and never allocates the agent one would have needed.
        final WorkflowRun run = build(jenkins, "eod-declarative", """
                pipeline {
                    agent none
                    options {
                        piplexExclusive(key: 'eod', designatedBy: 'eod', generation: '2026-09-12')
                    }
                    stages {
                        stage('EOD') { steps { echo 'this must not run' } }
                    }
                }
                """);

        jenkins.assertBuildStatus(Result.NOT_BUILT, run);
        jenkins.assertLogNotContains("this must not run", run);
    }

    @Test
    void publishesFromPostBeforeTheLeaseIsGivenBack(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");

        // completedWhen rests on publishing while the admission is held. In a declarative pipeline the
        // options{} block wraps post{} too, so the documented place to publish is inside it.
        final WorkflowRun run = build(jenkins, "eod-post-order", """
                pipeline {
                    agent none
                    options {
                        piplexExclusive(key: 'eod', generation: '2026-09-12', completedWhen: 'data/euc1')
                    }
                    stages {
                        stage('EOD') { steps { echo 'working' } }
                    }
                    post {
                        success { piplexPublish key: 'data/euc1', generation: '2026-09-12' }
                    }
                }
                """);

        jenkins.assertBuildStatus(Result.SUCCESS, run);
        final String log = run.getLog();
        final int published = log.indexOf("piplex: MILESTONE_PUBLISHED");
        final int released = log.indexOf("piplex: RELEASED");
        assertTrue(published >= 0 && released > published,
                "The milestone must be published before the lease goes back, log was:\n" + log);
    }

    @Test
    void keepsTheLeaseUntilAMilestoneOnItsWayWhenStoppedHasLanded(final JenkinsRule jenkins) throws Exception {
        PiplexConfiguration.get().setOwnerId("euc1-blue");
        final String milestone = Milestones.keyOf("data/euc1");
        final CompletableFuture<Void> publishAsked = new CompletableFuture<>();
        final CompletableFuture<Void> gate = new CompletableFuture<>();
        final AtomicBoolean published = new AtomicBoolean();
        final AtomicBoolean releasedFirst = new AtomicBoolean();
        PiplexConfiguration.useStore((CoordinationStore) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {CoordinationStore.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("compareAndSet") && milestone.equals(args[0])) {
                        publishAsked.complete(null);
                        return gate.thenCompose(ignored -> store.compareAndSet(
                                        (String) args[0], (String) args[1], (String) args[2]))
                                .whenComplete((swapped, error) -> published.set(true));
                    }
                    if (method.getName().equals("release") && !published.get()) {
                        releasedFirst.set(true);
                    }
                    return method.invoke(store, args);
                }), time);

        final WorkflowJob job = jenkins.createProject(WorkflowJob.class, "eod-stopped-while-publishing");
        job.setDefinition(new CpsFlowDefinition("""
                piplexExclusive(key: 'eod', generation: '2026-09-12', completedWhen: 'data/euc1') {
                    piplexPublish key: 'data/euc1', generation: '2026-09-12'
                }
                """, true));
        final WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        publishAsked.get(30L, TimeUnit.SECONDS);

        run.doStop();
        // Printed once the stop has been sent and the build is still going, which is the point: it
        // waits for the write rather than ending over it.
        jenkins.waitForMessage("forcibly terminate running steps", run);
        gate.complete(null);

        jenkins.waitForCompletion(run);
        jenkins.assertBuildStatus(Result.ABORTED, run);
        assertTrue(published.get(), "The milestone must have been written");
        assertFalse(releasedFirst.get(), "The lease must not go back before the milestone has landed");
    }

    @Test
    void waitsForAMilestoneAndThenCarriesOn(final JenkinsRule jenkins) throws Exception {
        configure("eus1-blue");

        final WorkflowJob waiting = jenkins.createProject(WorkflowJob.class, "new-day");
        waiting.setDefinition(new CpsFlowDefinition("""
                node {
                    piplexAwait key: 'data/euc1', generation: '2026-09-12', timeout: '2m'
                    echo 'the day is here'
                }
                """, true));
        final WorkflowRun run = waiting.scheduleBuild2(0).waitForStart();
        jenkins.waitForMessage("MILESTONE_WAITING", run);

        build(jenkins, "eod-publisher", """
                node {
                    piplexPublish key: 'data/euc1', generation: '2026-09-12'
                }
                """);

        jenkins.waitForCompletion(run);
        jenkins.assertBuildStatus(Result.SUCCESS, run);
        jenkins.assertLogContains("the day is here", run);
    }

    @Test
    void stopsWaitingForAMilestoneWhenTheBuildIsStopped(final JenkinsRule jenkins) throws Exception {
        PiplexConfiguration.get().setOwnerId("eus1-blue");
        final String key = Milestones.keyOf("data/euc1");
        final AtomicInteger reads = new AtomicInteger();
        PiplexConfiguration.useStore((CoordinationStore) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {CoordinationStore.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("get") && key.equals(args[0])) {
                        reads.incrementAndGet();
                    }
                    return method.invoke(store, args);
                }), time);

        final WorkflowJob waiting = jenkins.createProject(WorkflowJob.class, "new-day-stopped");
        waiting.setDefinition(new CpsFlowDefinition(
                "piplexAwait key: 'data/euc1', generation: '2026-09-12', timeout: '1h'", true));
        final WorkflowRun run = waiting.scheduleBuild2(0).waitForStart();
        jenkins.waitForMessage("MILESTONE_WAITING", run);

        run.doStop();
        jenkins.waitForCompletion(run);
        jenkins.assertBuildStatus(Result.ABORTED, run);

        // The publish wakes the watch still in flight, and a wait nobody is waiting on must end there
        // rather than look again for the rest of the hour.
        final int before = reads.get();
        new Milestones(store, time).publish("data/euc1", Generation.of("2026-09-11"), "euc1-blue", "eod#1")
                .toCompletableFuture().get(10L, TimeUnit.SECONDS);
        assertEquals(before, reads.get(), "A stopped wait must not read the key again");
    }

    @Test
    void saysWhatItFoundWhenAMilestoneNeverArrives(final JenkinsRule jenkins) throws Exception {
        configure("eus1-blue");

        final WorkflowRun run = build(jenkins, "new-day-timeout", """
                node {
                    piplexAwait key: 'data/euc1', generation: '2026-09-12', timeout: '2s'
                }
                """);

        jenkins.assertBuildStatus(Result.FAILURE, run);
        jenkins.assertLogContains("waited for 'data/euc1' to reach 2026-09-12", run);
    }

    private void configure(final String ownerId) {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setOwnerId(ownerId);
        PiplexConfiguration.useStore(store, time);
    }

    private void designate(final String owner) throws Exception {
        new Designations(store, time).designate("eod", owner, "handover")
                .toCompletableFuture().get(10L, TimeUnit.SECONDS);
    }

    // As the external system would: a plain value, no JSON.
    private void activate(final String value) throws Exception {
        final String version = store.get(ACTIVE).toCompletableFuture().get(10L, TimeUnit.SECONDS).version();
        assertTrue(store.compareAndSet(ACTIVE, version, value).toCompletableFuture().get(10L, TimeUnit.SECONDS));
    }

    private void disable(final String key, final String reason) throws Exception {
        new Switches(store, time).disable(key, reason)
                .toCompletableFuture().get(10L, TimeUnit.SECONDS);
    }

    /**
     * Asks for the lease as another controller would, until somebody gives it up or the wait is over.
     * The release happens off the build's thread, so it is waited for rather than assumed.
     *
     * @return whether the lease could be taken
     */
    private boolean leaseIsFree() throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30L);
        final ExclusiveRuns elsewhere = new ExclusiveRuns(store, time);
        while (System.nanoTime() - deadline < 0L) {
            final Admission asked = elsewhere.begin(ExclusiveRequest.builder("eod")
                            .ownedBy("eus1-blue")
                            .runId("next#1")
                            .build())
                    .toCompletableFuture().get(10L, TimeUnit.SECONDS);
            if (asked instanceof Admitted taken) {
                taken.release().toCompletableFuture().get(10L, TimeUnit.SECONDS);
                return true;
            }
            Thread.sleep(100L);
        }
        return false;
    }

    private WorkflowRun build(final JenkinsRule jenkins, final String name, final String script)
            throws Exception {
        final WorkflowJob job = jenkins.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition(script, true));
        return job.scheduleBuild2(0).get();
    }

    /**
     * Holds an acquisition open until the test lets it answer, so that something else can happen in the
     * moment between a step asking for the lease and being given one.
     */
    private static final class HeldUpAcquire implements CoordinationStore {

        private final CoordinationStore delegate;
        private final CompletableFuture<Void> asked = new CompletableFuture<>();
        private final CompletableFuture<Void> answer = new CompletableFuture<>();

        HeldUpAcquire(final CoordinationStore delegate) {
            this.delegate = delegate;
        }

        CompletableFuture<Void> asked() {
            return asked;
        }

        void answer() {
            answer.complete(null);
        }

        @Override
        public CompletionStage<LeaseAttempt> tryAcquire(final String key,
                                                        final String ownerId,
                                                        final Duration ttl) {
            asked.complete(null);
            return answer.thenCompose(ignored -> delegate.tryAcquire(key, ownerId, ttl));
        }

        @Override
        public CompletionStage<Entry> get(final String key) {
            return delegate.get(key);
        }

        @Override
        public CompletionStage<Boolean> compareAndSet(final String key,
                                                      final String expectedVersion,
                                                      final String value) {
            return delegate.compareAndSet(key, expectedVersion, value);
        }

        @Override
        public CompletionStage<Entry> awaitChange(final String key,
                                                  final String sinceVersion,
                                                  final Duration maxWait) {
            return delegate.awaitChange(key, sinceVersion, maxWait);
        }

        @Override
        public CompletionStage<Boolean> renew(final String key,
                                              final LeaseHandle handle,
                                              final Duration ttl) {
            return delegate.renew(key, handle, ttl);
        }

        @Override
        public CompletionStage<Void> release(final String key, final LeaseHandle handle) {
            return delegate.release(key, handle);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /**
     * Moves the designation after the lease is taken and re-read, and before the admission reaches the
     * step. Only the timing is arranged; the designation really moves and the run really is revoked for it.
     */
    private static final class DesignationMovesMidAcquire implements CoordinationStore {

        private final CoordinationStore delegate;
        private final TimeSource time;
        private final AtomicBoolean acquired = new AtomicBoolean();
        private final AtomicBoolean moved = new AtomicBoolean();

        DesignationMovesMidAcquire(final CoordinationStore delegate, final TimeSource time) {
            this.delegate = delegate;
            this.time = time;
        }

        @Override
        public CompletionStage<LeaseAttempt> tryAcquire(final String key,
                                                        final String ownerId,
                                                        final Duration ttl) {
            return delegate.tryAcquire(key, ownerId, ttl).thenApply(attempt -> {
                if (attempt instanceof LeaseAttempt.Acquired) {
                    acquired.set(true);
                }
                return attempt;
            });
        }

        @Override
        public CompletionStage<Entry> get(final String key) {
            final CompletionStage<Entry> read = delegate.get(key);
            if (acquired.get() && key.equals(Designations.keyOf("eod")) && moved.compareAndSet(false, true)) {
                new Designations(delegate, time)
                        .designate("eod", "eus1-blue", "the moment in between")
                        .toCompletableFuture().join();
            }
            return read;
        }

        @Override
        public CompletionStage<Boolean> compareAndSet(final String key,
                                                      final String expectedVersion,
                                                      final String value) {
            return delegate.compareAndSet(key, expectedVersion, value);
        }

        @Override
        public CompletionStage<Entry> awaitChange(final String key,
                                                  final String sinceVersion,
                                                  final Duration maxWait) {
            return delegate.awaitChange(key, sinceVersion, maxWait);
        }

        @Override
        public CompletionStage<Boolean> renew(final String key,
                                              final LeaseHandle handle,
                                              final Duration ttl) {
            return delegate.renew(key, handle, ttl);
        }

        @Override
        public CompletionStage<Void> release(final String key, final LeaseHandle handle) {
            return delegate.release(key, handle);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
