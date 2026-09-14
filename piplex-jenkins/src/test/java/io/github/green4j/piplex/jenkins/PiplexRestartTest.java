/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Result;
import io.github.green4j.piplex.TimeSource;
import io.github.green4j.piplex.exclusive.Designations;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.Entry;
import io.github.green4j.piplex.store.LeaseAttempt;
import io.github.green4j.piplex.store.LeaseHandle;
import io.github.green4j.piplex.store.memory.InMemoryCoordinationStore;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.JenkinsSessionRule;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

            assertEquals("a parked run must not hold an executor", 0, busyExecutors(jenkins));
        });

        session.then(jenkins -> {
            configure("eus1-blue");

            final WorkflowJob job = jenkins.jenkins.getItemByFullName("eod", WorkflowJob.class);
            final WorkflowRun run = job.getBuildByNumber(1);
            assertTrue("the wait must outlive the controller that was doing it", run.isBuilding());

            // A second PARKED line, and this is the assertion the test exists for: resuming is not the
            // plugin remembering that it was waiting, it is the plugin asking again from nothing and
            // arriving at the same answer. Nothing live survived the restart to be remembered.
            awaitTimes(run, "PARKED", 2);
            assertEquals("and must still not hold an executor after resuming", 0, busyExecutors(jenkins));

            // Frankfurt is out. Milan, which has been waiting since before the restart, takes over.
            designate("eus1-blue");

            jenkins.waitForCompletion(run);
            jenkins.assertBuildStatus(Result.SUCCESS, run);
            jenkins.assertLogContains("end of day ran here", run);
        });
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
                        sleep 300
                    }
                    """, true));
            final WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            jenkins.waitForMessage("the body started", run);
        });

        session.then(jenkins -> {
            configure("euc1-blue");

            final WorkflowJob job = jenkins.jenkins.getItemByFullName("eod-admitted", WorkflowJob.class);
            final WorkflowRun run = job.getBuildByNumber(1);
            assertTrue("the run must outlive the controller", run.isBuilding());

            // The ownership itself does not survive a restart -- the lease was not renewed while the
            // controller was down -- so the resumed body has to be admitted again before it may carry
            // on. That second ADMITTED is the point at which a duplicated body would announce itself.
            awaitTimes(run, "ADMITTED", 2);
            Thread.sleep(5000L);
            final int started = occurrences(run, "the body started");
            run.doStop();
            jenkins.waitForCompletion(run);

            assertEquals("the guarded body must not run a second time after a restart", 1, started);
        });
    }

    private static void configure(final String ownerId) {
        controllerWentAway();
        connection = new SessionStore(CLUSTER);
        controllerScheduler = Executors.newScheduledThreadPool(2);
        PiplexConfiguration.get().setOwnerId(ownerId);
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

    private static void designate(final String key, final String owner) throws Exception {
        new Designations(CLUSTER, TIME).designate(key, owner, "test", "handover")
                .toCompletableFuture().get(10L, TimeUnit.SECONDS);
    }

    private static void awaitTimes(final WorkflowRun run, final String text, final int times)
            throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60L);
        while (System.nanoTime() - deadline < 0L) {
            if (occurrences(run, text) >= times) {
                return;
            }
            Thread.sleep(200L);
        }
        throw new AssertionError("expected '" + text + "' " + times + " times, log was:\n" + run.getLog());
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

        SessionStore(final CoordinationStore cluster) {
            this.cluster = cluster;
        }

        void controllerWentAway() {
            gone = true;
            synchronized (waits) {
                waits.clear();
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
