/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import hudson.ExtensionList;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Result;
import io.github.green4j.piplex.Environment;
import io.github.green4j.piplex.TimeSource;
import io.github.green4j.piplex.exclusive.Admission;
import io.github.green4j.piplex.exclusive.Admitted;
import io.github.green4j.piplex.exclusive.Designations;
import io.github.green4j.piplex.exclusive.ExclusiveRequest;
import io.github.green4j.piplex.exclusive.ExclusiveRuns;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.Entry;
import io.github.green4j.piplex.store.LeaseAttempt;
import io.github.green4j.piplex.store.LeaseHandle;
import io.github.green4j.piplex.store.memory.InMemoryCoordinationStore;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.JenkinsSessionRule;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The claim that makes putting the same schedule on every controller reasonable, tested rather than
 * asserted.
 *
 * <p>Three controllers out of four spend the night waiting for a handover that will probably never
 * come. That is only acceptable if waiting is nearly free and nearly indestructible, so this asks the
 * two questions that decide it: does a waiting build hold an executor, and does it survive the
 * controller being restarted under it.
 *
 * <p>The second question is the interesting one, and the answer is not "the plugin saves its state". It
 * does not: what is written down is a handful of strings, and everything live -- the admission, its
 * renewals, its watches -- is gone. On resume it simply asks again. That is correct only because asking
 * is idempotent and because the core compares state instead of counting events, which is the same
 * property that lets a waiter be restarted at all.
 *
 * <p>JUnit 4, because restarting Jenkins needs {@link JenkinsSessionRule} and that is a JUnit 4 rule.
 */
public class PiplexRestartTest {

    // Generous, for a slow runner: a state that is coming arrives long before, and one that is not
    // fails the test either way.
    private static final long AWAIT_SECONDS = 120L;
    private static final long POLL_MILLIS = 200L;
    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(2);
    private static final TimeSource TIME = TimeSource.of(SCHEDULER);

    // Static, and shared by both halves of the test: it stands for the discas cluster, which is the one
    // thing that does not restart when a controller does.
    private static final InMemoryCoordinationStore CLUSTER = new InMemoryCoordinationStore(TIME);

    // And these are the controller's, which does restart. A real restart takes the client and its
    // timers with it; inside one JVM they have to be taken by hand, or the old park loop wakes a step
    // belonging to a Jenkins that has been shut down.
    private static volatile SessionStore connection;
    private static volatile ScheduledExecutorService controllerScheduler;

    @Rule
    public JenkinsSessionRule session = new JenkinsSessionRule();

    /**
     * Puts the real store back.
     *
     * <p>{@code useStore} sets a static, and nothing else clears it. Left set, it is inherited by
     * whichever class runs next, which then asks a store this one made about keys it never wrote --
     * a failure that moves when a test class is renamed.
     */
    @After
    public void releaseTheSuppliedStore() {
        PiplexConfiguration.useStore(null, null);
    }

    @Test
    public void aParkedRunSurvivesARestartAndThenTakesOver() throws Throwable {
        session.then(jenkins -> {
            configure("eus1-blue");
            designate("euc1-blue");

            final WorkflowJob job = jenkins.createProject(WorkflowJob.class, "eod");
            // The node block is inside, which is the whole point: nothing is allocated until this
            // controller is the one that has to run.
            job.setDefinition(new CpsFlowDefinition("""
                    piplexExclusive(key: 'eod', designatedBy: 'eod', generation: '2026-09-12',
                                    lease: '5s', handoverWait: '30m') {
                        node { echo 'end of day ran here' }
                    }
                    """, true));
            final WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            jenkins.waitForMessage("PARKED", run);

            assertEquals("A parked run must not hold an executor", 0, busyExecutors(jenkins));
        });

        controllerCameBack();

        session.then(jenkins -> {
            final WorkflowJob job = jenkins.jenkins.getItemByFullName("eod", WorkflowJob.class);
            final WorkflowRun run = job.getBuildByNumber(1);
            assertTrue("The wait must outlive the controller that was doing it", run.isBuilding());

            // A second PARKED line, and this is the assertion the test exists for: resuming is not the
            // plugin remembering that it was waiting, it is the plugin asking again from nothing and
            // arriving at the same answer. Nothing live survived the restart to be remembered.
            awaitTimes(run, "PARKED", 2);
            assertEquals("And must still not hold an executor after resuming", 0, busyExecutors(jenkins));

            // Frankfurt is out. Milan, which has been waiting since before the restart, takes over.
            designate("eus1-blue");

            jenkins.waitForCompletion(run);
            jenkins.assertBuildStatus(Result.SUCCESS, run);
            jenkins.assertLogContains("end of day ran here", run);
        });
    }

    @Test
    public void aParkedRunKeepsWhatItAlreadyWaitedAcrossARestart() throws Throwable {
        session.then(jenkins -> {
            configure("eus1-blue");
            designate("eod-waited", "euc1-blue");

            final WorkflowJob job = jenkins.createProject(WorkflowJob.class, "eod-waited");
            job.setDefinition(new CpsFlowDefinition("""
                    piplexExclusive(key: 'eod-waited', designatedBy: 'eod-waited',
                                    generation: '2026-09-12', lease: '3s', handoverWait: '1h') {
                        echo 'this must not run'
                    }
                    """, true));
            final WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            jenkins.waitForMessage("PARKED", run);
            // A few of the second-long ticks the wait is written down on.
            awaitUntil(() -> "the step wrote down some waiting, log was:\n" + run.getLog(),
                    () -> waited(run).compareTo(Duration.ofSeconds(4)) >= 0);
        });

        controllerCameBack();

        session.then(jenkins -> {
            final WorkflowJob job = jenkins.jenkins.getItemByFullName("eod-waited", WorkflowJob.class);
            final WorkflowRun run = job.getBuildByNumber(1);
            awaitParkedAfterResume(run);

            // The handover window is the job's, not the controller's: a restart must not open a new one.
            final Duration remaining = firstParkedAfterResume(run);
            assertTrue("The wait before the restart must count, but " + remaining + " was left",
                    remaining.compareTo(Duration.ofHours(1).minusSeconds(3)) < 0);

            run.doStop();
            jenkins.waitForCompletion(run);
        });
    }

    private static void awaitParkedAfterResume(final WorkflowRun run) throws Exception {
        awaitUntil(() -> "the resumed run parked, log was:\n" + run.getLog(),
                () -> firstParkedAfterResume(run) != null);
    }

    /**
     * @param run the build whose exclusive step is asked
     * @return how much waiting the step has written down
     * @throws Exception if the build's steps cannot be read
     */
    private static Duration waited(final WorkflowRun run) throws Exception {
        final List<StepExecution> steps = run.getExecution().getCurrentExecutions(true).get(10L, TimeUnit.SECONDS);
        for (final StepExecution step : steps) {
            if (step instanceof ExclusiveStepExecution exclusive) {
                return exclusive.waited();
            }
        }
        return Duration.ZERO;
    }

    /**
     * Asks until the answer is yes. The one place this test waits in real time, and it waits for a
     * state, never for a guessed interval.
     *
     * @param what      what is being waited for, asked only when giving up
     * @param condition the question
     * @throws Exception if asking failed, or the answer never came
     */
    private static void awaitUntil(final Callable<String> what, final Callable<Boolean> condition)
            throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (!condition.call()) {
            if (System.nanoTime() - deadline >= 0L) {
                throw new AssertionError("Gave up waiting until " + what.call());
            }
            Thread.sleep(POLL_MILLIS);
        }
    }

    private static Duration firstParkedAfterResume(final WorkflowRun run) throws Exception {
        final String log = run.getLog();
        final int resumed = log.indexOf("Resuming build");
        if (resumed < 0) {
            return null;
        }
        final Matcher parked = Pattern.compile("PARKED .*remaining=(PT\\S+)")
                .matcher(log.substring(resumed));
        return parked.find() ? Duration.parse(parked.group(1)) : null;
    }

    @Test
    public void anAdmittedRunSurvivesARestartWithoutRunningTheBodyTwice() throws Throwable {
        session.then(jenkins -> {
            configure("euc1-blue");
            designate("eod-admitted", "euc1-blue");

            final WorkflowJob job = jenkins.createProject(WorkflowJob.class, "eod-admitted");
            job.setDefinition(new CpsFlowDefinition("""
                    piplexExclusive(key: 'eod-admitted', designatedBy: 'eod-admitted',
                                    generation: '2026-09-12', lease: '30s') {
                        echo 'the body started'
                        // Not waitUntil: between bodies it writes down that none is running, and a
                        // write during shutdown is skipped -- resumed, it waits forever on a body
                        // which ended before the restart. sleep re-arms from a written-down deadline.
                        while (currentBuild.description != 'go') {
                            sleep 1
                        }
                    }
                    """, true));
            final WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            jenkins.waitForMessage("the body started", run);
        });

        controllerCameBack();

        session.then(jenkins -> {
            final WorkflowJob job = jenkins.jenkins.getItemByFullName("eod-admitted", WorkflowJob.class);
            final WorkflowRun run = job.getBuildByNumber(1);
            assertTrue("The run must outlive the controller", run.isBuilding());

            // The ownership itself does not survive a restart -- the lease was not renewed while the
            // controller was down -- so the resumed body has to be admitted again before it may carry
            // on. That second ADMITTED is the point at which a duplicated body would announce itself.
            awaitTimes(run, "ADMITTED", 2);
            // Let the body finish. A second one, started on the way back in, would pass the same gate
            // and print its line before the build could end.
            run.setDescription("go");
            awaitCompletion(run);

            jenkins.assertBuildStatus(Result.SUCCESS, run);
            assertEquals("The guarded body must not run a second time after a restart",
                    1, occurrences(run, "the body started"));
        });
    }

    /**
     * The other half of the resume, and the one a test is actually needed for: the work carried on
     * across the restart, and the lease that was not renewed while the controller was down has been
     * taken by somebody else. What the body is owed then is to be stopped.
     *
     * <p>Nothing takes the controller's timers away by hand here, so the lease being free to take also
     * says that stopping Jenkins stopped the renewals, in a JVM which does not stop with it.
     */
    @Test
    public void aResumedBodyIsStoppedWhenTheOwnershipCannotBeRetaken() throws Throwable {
        startLongBody("eod-taken");

        takeTheLease("eod-taken", "eus1-blue");
        controllerCameBack();

        // Aborted rather than not built: this build built something before the restart.
        assertResumedAs("eod-taken", Result.ABORTED, "after a restart");
    }

    /**
     * The half of the resume nobody expects: the ownership is retaken, and it is still not the one the
     * body is running under.
     *
     * <p>While the controller was down the lease lapsed, somebody else took it and gave it back, so the
     * resumed run is admitted under a new lease with a higher fencing token -- while the body has already
     * handed the old one to whatever it protects. The log names both numbers.
     */
    @Test
    public void aResumedBodyIsStoppedWhenTheOwnershipComesBackAsADifferentLease() throws Throwable {
        startLongBody("eod-refenced");

        controllerWentAway();
        takeTheLease("eod-refenced", "eus1-blue").release().toCompletableFuture()
                .get(10L, TimeUnit.SECONDS);
        controllerCameBack();

        assertResumedAs("eod-refenced", Result.ABORTED, "fencing token");
    }

    /**
     * A restart is not a reason to change what a broken key means.
     *
     * <p>A value somebody has to fix is the one outcome that is nobody's decision, and it is the one that
     * is red. An abort says somebody else has the work, which is the one thing not known here.
     */
    @Test
    public void aResumedRunFailsRatherThanAbortsWhenAGuardWillNotParse() throws Throwable {
        startLongBody("eod-scribbled");

        // Edited by hand while the controller is down: nothing is watching the key, so what finds it is
        // the resume asking again from scratch.
        controllerWentAway();
        scribbleOn("eod-scribbled");
        controllerCameBack();

        assertResumedAs("eod-scribbled", Result.FAILURE, Designations.keyOf(Environment.DEFAULT, "eod-scribbled"));
    }

    /**
     * A resume that cannot even ask stops the body, rather than answering the step while it runs.
     */
    @Test
    public void aResumedBodyIsAbortedWhenTheStoreFailsToAnswer() throws Throwable {
        startLongBody("eod-unreachable");

        controllerCameBack();
        // Answered, and answered with a failure: the store is reachable enough to say no.
        connection.failReads();

        assertResumedAs("eod-unreachable", Result.ABORTED, "could not be checked");
    }

    @Test
    public void aResumedBodyIsAbortedWhenTheConfigurationIsGone() throws Throwable {
        startLongBody("eod-unconfigured");

        controllerWentAway();
        Files.delete(session.getHome().toPath()
                .resolve(PiplexConfiguration.class.getName() + ".xml"));
        controllerCameBack();

        assertResumedAs("eod-unconfigured", Result.ABORTED, "owner id");
    }

    /**
     * A resumed step whose ask is refused still counts as running here until its body has ended.
     *
     * <p>The refusal cancels the body, and cancelling is asynchronous: the guarded work runs on until
     * it notices. Enrolled only after the ask succeeded, the step would be in nothing for the whole of
     * that window and a drain would call this controller quiet with the work still going.
     *
     * @throws Throwable if the session fails
     */
    @Test
    public void countsAResumedBodyAsRunningWhileAskingAgainIsRefused() throws Throwable {
        session.then(jenkins -> {
            configure("euc1-blue");
            designate("eod-resumed", "euc1-blue");
            ExtensionList.lookup(StepDescriptor.class).add(new PiplexStepsTest.SlowToStop.DescriptorImpl());
            PiplexStepsTest.SlowToStop.reset();

            final WorkflowJob job = jenkins.createProject(WorkflowJob.class, "eod-resumed");
            job.setDefinition(new CpsFlowDefinition("""
                    piplexExclusive(key: 'eod-resumed', designatedBy: 'eod-resumed',
                                    enabledBy: 'eod-switch', generation: '2026-09-12', lease: '2s') {
                        slowToStop()
                    }
                    """, true));
            final WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            jenkins.waitForMessage("working", run);
        });

        controllerWentAway();
        Files.delete(session.getHome().toPath()
                .resolve(PiplexConfiguration.class.getName() + ".xml"));
        controllerCameBack();

        session.then(jenkins -> {
            final WorkflowRun run = jenkins.jenkins
                    .getItemByFullName("eod-resumed", WorkflowJob.class).getBuildByNumber(1);

            PiplexStepsTest.SlowToStop.stopAsked.get(AWAIT_SECONDS, TimeUnit.SECONDS);
            assertTrue("A body told to stop and still running is work this controller has to wait for",
                    ExclusiveStepExecution.anyRunningUnder("eod-switch", Environment.DEFAULT));

            PiplexStepsTest.SlowToStop.letGo.complete(null);
            awaitCompletion(run);
            awaitUntil(() -> "the ended body left the set",
                    () -> !ExclusiveStepExecution.anyRunningUnder("eod-switch", Environment.DEFAULT));
        });
    }

    /**
     * Starts a build whose body runs for minutes under a lease of two seconds, as euc1-blue and designated.
     *
     * @param key the job, the key and the designation
     * @throws Throwable if the session fails
     */
    private void startLongBody(final String key) throws Throwable {
        session.then(jenkins -> {
            configure("euc1-blue");
            designate(key, "euc1-blue");

            final WorkflowJob job = jenkins.createProject(WorkflowJob.class, key);
            job.setDefinition(new CpsFlowDefinition("""
                    piplexExclusive(key: '%1$s', designatedBy: '%1$s', generation: '2026-09-12', lease: '2s') {
                        echo "the body started with ${env.PIPLEX_FENCING_TOKEN}"
                        sleep 300
                    }
                    """.formatted(key), true));
            final WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            jenkins.waitForMessage("the body started", run);
        });
    }

    /**
     * @param key    the job {@link #startLongBody} started
     * @param result what the resumed build has to end with
     * @param said   what its log has to say about why
     * @throws Throwable if the session fails
     */
    private void assertResumedAs(final String key, final Result result, final String said) throws Throwable {
        session.then(jenkins -> {
            final WorkflowRun run = jenkins.jenkins.getItemByFullName(key, WorkflowJob.class).getBuildByNumber(1);

            jenkins.waitForCompletion(run);
            jenkins.assertBuildStatus(result, run);
            assertTrue("The build has to say why, log was:\n" + run.getLog(), run.getLog().contains(said));
        });
    }

    /**
     * The resume nobody expects: the body is over before the ownership it was asking for comes back.
     *
     * <p>A resumed step asks again while Jenkins replays the body, and the two are not in step -- the
     * body is replayed from where it stopped and can reach its end while the store is still being
     * asked. What arrives then is an admission for a step which has finished: there is no body to
     * stop, no context to answer, and nothing in the answer itself to say so. Left held, that lease is
     * renewed for as long as the controller runs, and every other controller waits for a run which
     * ended minutes ago.
     */
    @Test
    public void givesTheLeaseBackWhenTheResumedBodyEndsBeforeTheAdmissionArrives() throws Throwable {
        session.then(jenkins -> {
            configure("euc1-blue");
            designate("eod-quick", "euc1-blue");

            final WorkflowJob job = jenkins.createProject(WorkflowJob.class, "eod-quick");
            // A lease long enough that it cannot lapse its way out of this test: what gives it back
            // has to be the step, not the clock.
            job.setDefinition(new CpsFlowDefinition("""
                    piplexExclusive(key: 'eod-quick', designatedBy: 'eod-quick',
                                    generation: '2026-09-12', lease: '10m') {
                        echo 'the body started'
                        sleep 5
                    }
                    """, true));
            final WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            jenkins.waitForMessage("the body started", run);
        });

        controllerCameBack();
        // The controller is back and the cluster is slow to answer the one question the resume asks.
        // Slow enough that the body -- which has a few seconds of sleep left and knows nothing of any
        // of this -- runs out first.
        connection.holdAcquisitions();

        session.then(jenkins -> {
            final WorkflowJob job = jenkins.jenkins.getItemByFullName("eod-quick", WorkflowJob.class);
            final WorkflowRun run = job.getBuildByNumber(1);

            jenkins.waitForCompletion(run);

            connection.letAcquisitionsThrough();

            // Nobody is left to hold it, so it has to come back at once rather than in ten minutes.
            assertTheLeaseIsFree("eod-quick");
        });
    }

    /**
     * The restart that lands in the one gap a step cannot ask its way out of.
     *
     * <p>A body ends, and the step does not answer its context there and then: it gives the lease back
     * first, so that the controller taking over is not left waiting out a lease nobody holds. That
     * release is a round trip, and a controller which goes down inside it comes back to a step whose
     * body is over, whose context was never answered, and whose body -- replayed by Jenkins -- is
     * finished before anything looks at it.
     *
     * <p>Asking again there is the wrong answer twice over. The lease it takes is for a night that is
     * done, and the body it then tries to stop, over a fencing token it never used, has already
     * stopped -- so nothing calls back, nothing answers the context, and the lease it is holding is
     * renewed for as long as the controller runs. Both halves are asserted: the build reaches a
     * result, and the key is free afterwards.
     */
    @Test
    public void answersForABodyWhichEndedWhileTheLeaseWasGoingBack() throws Throwable {
        session.then(jenkins -> {
            configure("euc1-blue");
            designate("eod-releasing", "euc1-blue");
            // From here the cluster never answers a release, which is where this controller is going
            // to be stopped.
            connection.holdReleases();

            final WorkflowJob job = jenkins.createProject(WorkflowJob.class, "eod-releasing");
            // Short, because what has to happen to the lease abandoned below is that it lapses: the
            // controller that took it is gone, and nothing is left to give it back.
            job.setDefinition(new CpsFlowDefinition("""
                    piplexExclusive(key: 'eod-releasing', designatedBy: 'eod-releasing',
                                    generation: '2026-09-12', lease: '5s') {
                        echo 'the body is over'
                    }
                    """, true));
            final WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            jenkins.waitForMessage("the body is over", run);

            // Waited for rather than assumed: the body ending and the step reaching the release are two
            // things, and only the second one is the window this test is about.
            awaitAReleaseInFlight();
            assertTrue("The step must still be waiting on the release it will be stopped inside",
                    run.isBuilding());
        });

        controllerCameBack();

        session.then(jenkins -> {
            final WorkflowJob job =
                    jenkins.jenkins.getItemByFullName("eod-releasing", WorkflowJob.class);
            final WorkflowRun run = job.getBuildByNumber(1);

            // Not waitForCompletion: a step which never answers would hang this for as long as the
            // suite is allowed to run, and what it is being asked is precisely whether it answers.
            awaitCompletion(run);
            jenkins.assertBuildStatus(Result.SUCCESS, run);
            jenkins.assertLogContains("the body is over", run);

            // The abandoned lease lapses, and nothing new may be holding one: a step that asked again
            // here would be renewing what it took for work that finished before the restart.
            assertTheLeaseIsFree("eod-releasing");
        });
    }

    /**
     * @throws AssertionError if no release is ever asked for
     */
    private static void awaitAReleaseInFlight() throws Exception {
        awaitUntil(() -> "the step asked for the lease to be given back", () -> connection.releasesHeld() > 0);
    }

    /**
     * @param run the build to wait for
     * @throws AssertionError if it never reaches a result
     */
    private static void awaitCompletion(final WorkflowRun run) throws Exception {
        awaitUntil(() -> "the build ended, log was:\n" + run.getLog(), () -> !run.isBuilding());
    }

    private static void assertTheLeaseIsFree(final String key) throws Exception {
        final ExclusiveRuns elsewhere = new ExclusiveRuns(CLUSTER, TIME);
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30L);
        Admission asked = null;
        while (System.nanoTime() - deadline < 0L) {
            asked = elsewhere.begin(ExclusiveRequest.builder(key)
                            .ownedBy("eus1-blue")
                            .runId("nightly #1")
                            .lease(Duration.ofMinutes(1))
                            .build())
                    .toCompletableFuture().get(10L, TimeUnit.SECONDS);
            if (asked instanceof Admitted taken) {
                taken.release().toCompletableFuture().get(10L, TimeUnit.SECONDS);
                return;
            }
            Thread.sleep(POLL_MILLIS);
        }
        throw new AssertionError(
                "The lease of a step whose body has finished was never given back: " + asked);
    }

    private static Admitted takeTheLease(final String key, final String owner) throws Exception {
        final ExclusiveRuns elsewhere = new ExclusiveRuns(CLUSTER, TIME);
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60L);
        while (System.nanoTime() - deadline < 0L) {
            final Admission asked = elsewhere.begin(ExclusiveRequest.builder(key)
                            .ownedBy(owner)
                            .runId("nightly #1")
                            .lease(Duration.ofMinutes(10))
                            .build())
                    .toCompletableFuture().get(10L, TimeUnit.SECONDS);
            if (asked instanceof Admitted taken) {
                return taken;
            }
            // The lease of the run that was interrupted is still standing; it lapses on its own.
            Thread.sleep(POLL_MILLIS);
        }
        throw new AssertionError("The lapsed lease was never free to take");
    }

    private static void configure(final String ownerId) {
        controllerCameBack();
        PiplexConfiguration.get().setOwnerId(ownerId);
    }

    /**
     * The connection and the timers a controller comes back with. Called before the session starts, not
     * from inside it: resuming a build is among the first things a controller does, and the step that
     * resumes asks for the store straight away -- after that, taking the previous session's scheduler
     * away leaves the resumed park loop scheduling on a dead one.
     */
    private static void controllerCameBack() {
        controllerWentAway();
        connection = new SessionStore(CLUSTER);
        controllerScheduler = Executors.newScheduledThreadPool(2);
        PiplexConfiguration.useStore(connection, TimeSource.of(controllerScheduler));
    }

    /**
     * Ends everything the controller was holding: outstanding waits never complete, timers never fire.
     */
    private static void controllerWentAway() {
        final SessionStore store = connection;
        if (store != null) {
            store.controllerWentAway();
        }
        final ScheduledExecutorService scheduler = controllerScheduler;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private static void designate(final String owner) throws Exception {
        designate("eod", owner);
    }

    /**
     * Puts something in a designation key that is not a designation, the way a hand edit does.
     *
     * @param key the key to break
     * @throws Exception if the cluster cannot be written to
     */
    private static void scribbleOn(final String key) throws Exception {
        final String path = Designations.keyOf(Environment.DEFAULT, key);
        final Entry asRead = CLUSTER.get(path).toCompletableFuture().get(10L, TimeUnit.SECONDS);
        CLUSTER.compareAndSet(path, asRead.version(), "euc1-blue")
                .toCompletableFuture().get(10L, TimeUnit.SECONDS);
    }

    private static void designate(final String key, final String owner) throws Exception {
        new Designations(CLUSTER, TIME).designate(key, owner, "handover")
                .toCompletableFuture().get(10L, TimeUnit.SECONDS);
    }

    private static void awaitTimes(final WorkflowRun run, final String text, final int times)
            throws Exception {
        awaitUntil(() -> "'" + text + "' was logged " + times + " times, log was:\n" + run.getLog(),
                () -> occurrences(run, text) >= times);
    }

    private static int occurrences(final WorkflowRun run, final String text) throws Exception {
        return run.getLog().split(text, -1).length - 1;
    }

    /**
     * One controller's connection to the cluster. Everything is the cluster's except the waits, which go
     * when the controller does.
     */
    private static final class SessionStore implements CoordinationStore {

        private final CoordinationStore cluster;
        private final List<CompletableFuture<Entry>> waits = new ArrayList<>();
        private volatile boolean gone;
        private volatile boolean failingReads;
        // Acquisitions taken and not yet answered, or null while the cluster answers at once.
        private List<Runnable> held;
        // And the same for releases, kept apart because a test holds one or the other and never both:
        // the two are the opposite ends of a step's life.
        private List<Runnable> heldReleases;

        SessionStore(final CoordinationStore cluster) {
            this.cluster = cluster;
        }

        void failReads() {
            failingReads = true;
        }

        void controllerWentAway() {
            gone = true;
            synchronized (waits) {
                waits.clear();
            }
        }

        /**
         * Holds every acquisition until {@link #letAcquisitionsThrough()}, which is what a cluster
         * taking its time looks like from here -- and the only way to put a step's question and the
         * body it belongs to in the order this test is about.
         */
        void holdAcquisitions() {
            held = new ArrayList<>();
        }

        void letAcquisitionsThrough() {
            final List<Runnable> waiting;
            synchronized (this) {
                waiting = held;
                held = null;
            }
            if (waiting != null) {
                waiting.forEach(Runnable::run);
            }
        }

        /**
         * Holds every release, the way a cluster taking its time does. It is the only way to stop a
         * controller <b>inside</b> the wait a step makes between its body ending and its context being
         * answered, which is a window nothing else in this fixture can reach.
         */
        void holdReleases() {
            synchronized (this) {
                heldReleases = new ArrayList<>();
            }
        }

        /**
         * @return how many releases are being held, which is how a test waits for the step to reach
         *         that wait rather than guessing that it has
         */
        int releasesHeld() {
            synchronized (this) {
                return heldReleases == null ? 0 : heldReleases.size();
            }
        }

        @Override
        public CompletionStage<Entry> awaitChange(final String key,
                                                  final String sinceVersion,
                                                  final Duration maxWait) {
            final CompletableFuture<Entry> result = new CompletableFuture<>();
            synchronized (waits) {
                waits.add(result);
            }
            cluster.awaitChange(key, sinceVersion, maxWait).whenComplete((entry, error) -> {
                if (gone) {
                    return; // nobody is left to be told
                }
                synchronized (waits) {
                    waits.remove(result);
                }
                if (error != null) {
                    result.completeExceptionally(error);
                } else {
                    result.complete(entry);
                }
            });
            return result;
        }

        @Override
        public CompletionStage<Entry> get(final String key) {
            if (failingReads) {
                return CompletableFuture.failedFuture(new IOException("The cluster is unreachable"));
            }
            return cluster.get(key);
        }

        @Override
        public CompletionStage<Boolean> compareAndSet(final String key,
                                                      final String expectedVersion,
                                                      final String value) {
            return cluster.compareAndSet(key, expectedVersion, value);
        }

        @Override
        public CompletionStage<LeaseAttempt> tryAcquire(final String key,
                                                        final String ownerId,
                                                        final Duration ttl) {
            synchronized (this) {
                if (held != null) {
                    final CompletableFuture<LeaseAttempt> deferred = new CompletableFuture<>();
                    held.add(() -> cluster.tryAcquire(key, ownerId, ttl)
                            .whenComplete((attempt, error) -> {
                                if (error != null) {
                                    deferred.completeExceptionally(error);
                                } else {
                                    deferred.complete(attempt);
                                }
                            }));
                    return deferred;
                }
            }
            return cluster.tryAcquire(key, ownerId, ttl);
        }

        @Override
        public CompletionStage<Boolean> renew(final String key,
                                              final LeaseHandle handle,
                                              final Duration ttl) {
            return cluster.renew(key, handle, ttl);
        }

        @Override
        public CompletionStage<Void> release(final String key, final LeaseHandle handle) {
            synchronized (this) {
                if (heldReleases != null) {
                    final CompletableFuture<Void> deferred = new CompletableFuture<>();
                    heldReleases.add(() -> cluster.release(key, handle)
                            .whenComplete((ignored, error) -> {
                                if (error != null) {
                                    deferred.completeExceptionally(error);
                                } else {
                                    deferred.complete(null);
                                }
                            }));
                    return deferred;
                }
            }
            return cluster.release(key, handle);
        }

        @Override
        public void close() {
            // The cluster outlives every controller connected to it, so this closes nothing.
            controllerWentAway();
        }
    }

    private static int busyExecutors(final JenkinsRule jenkins) {
        int busy = 0;
        for (final Computer computer : jenkins.jenkins.getComputers()) {
            for (final Executor executor : computer.getExecutors()) {
                if (executor.isBusy()) {
                    busy++;
                }
            }
        }
        return busy;
    }
}
