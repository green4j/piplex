/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    void stopsTheRunningBuildWhenTheDesignationMoves(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");
        designate("euc1-blue");

        final WorkflowJob job = jenkins.createProject(WorkflowJob.class, "eod-handover");
        job.setDefinition(new CpsFlowDefinition("""
                node {
                    piplexExclusive(key: 'eod', designatedBy: 'eod', generation: '2026-09-12',
                                    lease: '5s') {
                        echo 'working'
                        sleep 120
                    }
                }
                """, true));
        final WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        jenkins.waitForMessage("working", run);

        designate("eus1-blue");

        jenkins.waitForCompletion(run);
        // Aborted, not failed and not skipped: work had started, and it was stopped.
        jenkins.assertBuildStatus(Result.ABORTED, run);
        assertTrue(run.getLog().contains("DESIGNATION_CHANGED"),
                "the build log has to say why it stopped, or nobody can tell this from a flake");
    }

    @Test
    void doesNotRunTheBodyWhenOwnershipIsGoneBeforeItCouldStart(final JenkinsRule jenkins)
            throws Exception {
        designate("euc1-blue");
        // The designation moves between the lease being taken and the admission being handed back, so
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
        new Designations(store, time).designate("eod", owner, "test", "handover")
                .toCompletableFuture().get(10L, TimeUnit.SECONDS);
    }

    private WorkflowRun build(final JenkinsRule jenkins, final String name, final String script)
            throws Exception {
        final WorkflowJob job = jenkins.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition(script, true));
        return job.scheduleBuild2(0).get();
    }

    /**
     * Moves the designation after the lease is taken and before the admission reaches the step. Only the
     * timing is arranged; the designation really moves and the run really is revoked for it.
     */
    private static final class DesignationMovesMidAcquire implements CoordinationStore {

        private final CoordinationStore delegate;
        private final TimeSource time;
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
                if (attempt instanceof LeaseAttempt.Acquired && moved.compareAndSet(false, true)) {
                    new Designations(delegate, time)
                            .designate("eod", "eus1-blue", "test", "the moment in between")
                            .toCompletableFuture().join();
                }
                return attempt;
            });
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
}
