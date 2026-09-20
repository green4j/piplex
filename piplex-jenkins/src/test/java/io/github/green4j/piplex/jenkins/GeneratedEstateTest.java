/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import hudson.model.ParametersAction;
import hudson.model.Result;
import hudson.model.StringParameterValue;
import io.github.green4j.piplex.Environment;
import io.github.green4j.piplex.TimeSource;
import io.github.green4j.piplex.exclusive.Designations;
import io.github.green4j.piplex.init.Casc;
import io.github.green4j.piplex.init.Estate;
import io.github.green4j.piplex.init.Estate.Controller;
import io.github.green4j.piplex.init.Estate.Grants;
import io.github.green4j.piplex.init.Estate.Operator;
import io.github.green4j.piplex.init.Estate.Transport;
import io.github.green4j.piplex.init.Estate.Work;
import io.github.green4j.piplex.init.Pipelines;
import io.github.green4j.piplex.milestone.Milestone;
import io.github.green4j.piplex.observe.PiplexObserver;
import io.github.green4j.piplex.milestone.Milestones;
import io.github.green4j.piplex.store.memory.InMemoryCoordinationStore;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The quick start, run.
 *
 * <p>A newcomer's first piplex build is now whatever piplex-init wrote for them, so this takes the
 * block that tool generates, puts it in a declarative pipeline, and runs it on a controller
 * configured the way the configuration it generates configures one. Nothing here is typed out: a
 * generated block that does not run is a first five minutes that ends in a stack trace.
 */
@WithJenkins
class GeneratedEstateTest {

    private static final Controller BLUE = new Controller("euc1-blue", "piplex-euc1-blue");
    private static final Controller GREEN = new Controller("euc1-green", "piplex-euc1-green");

    private static final Estate ESTATE = new Estate(Environment.of("prod"), List.of(BLUE, GREEN),
            "n1=10.0.0.11:7101", Transport.ALLOWALL, Estate.SECRETS, Operator.SHARED,
            List.of(Work.of("eod", "eod-switch", "eod-owner", null, "data/euc1")),
            Grants.PER_KEY);

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final TimeSource time = TimeSource.of(scheduler);
    private InMemoryCoordinationStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryCoordinationStore(time);
    }

    @AfterEach
    void releaseTheSuppliedStore() {
        PiplexConfiguration.useStore(null, null);
    }

    @Test
    void runsTheBlockItGenerates(final JenkinsRule jenkins) throws Exception {
        configure(BLUE);
        designate("euc1-blue");

        final WorkflowRun run = build(jenkins, "generated-eod");

        jenkins.assertBuildStatus(Result.SUCCESS, run);
        jenkins.assertLogContains("piplex: ADMITTED environment=prod key=eod", run);
        jenkins.assertLogContains("end of day ran here", run);
        assertEquals("2026-09-14", Milestone.parse(store.get(
                        Milestones.keyOf(ESTATE.environment(), "data/euc1"))
                .toCompletableFuture().get().value()).generation().value());
    }

    // The other controller runs the same pipeline. Designation says it is not the one, and what it
    // must not do is fail: a standby that goes red every night is a standby somebody switches off.
    @Test
    void standsAsideOnTheControllerTheWorkWasNotSentTo(final JenkinsRule jenkins) throws Exception {
        configure(GREEN);
        designate("euc1-blue");

        final WorkflowRun run = build(jenkins, "generated-eod-green");

        jenkins.assertBuildStatus(Result.NOT_BUILT, run);
        jenkins.assertLogNotContains("end of day ran here", run);
    }

    // What the generated configuration says, applied through the same setters Configuration as Code
    // calls. The check is that the estate and the running controller agree on the two names that
    // tell controllers apart -- the third file, the ACL, grants exactly these.
    @Test
    void configuresTheControllerItDescribes(final JenkinsRule jenkins) throws Exception {
        configure(GREEN);

        final String yaml = Casc.render(ESTATE, GREEN);
        final PiplexConfiguration configuration = PiplexConfiguration.get();

        assertEquals("euc1-green", configuration.getOwnerId());
        assertEquals("piplex-euc1-green", configuration.getClientId());
        assertEquals("prod", configuration.getEnvironment());
        for (final String said : List.of("ownerId: \"euc1-green\"",
                "clientId: \"piplex-euc1-green\"", "environment: \"prod\"")) {
            assertTrue(yaml.contains(said), yaml);
        }
    }

    private WorkflowRun build(final JenkinsRule jenkins, final String name) throws Exception {
        final WorkflowJob project = jenkins.createProject(WorkflowJob.class, name);
        project.setDefinition(new CpsFlowDefinition(pipeline(), true));
        // Supplied rather than left to the declaration's own default: a job learns its parameters
        // from a build, so the first build of a fresh one has none. Nothing to do with piplex, and
        // nothing a deployment meets -- its jobs are triggered, and a trigger carries the date.
        return project.scheduleBuild2(0, new ParametersAction(
                new StringParameterValue("BUSINESS_DATE", "2026-09-14"))).get();
    }

    // A declarative pipeline of the shape the quick start shows, with the generated block pasted in
    // where the tool says to paste it and nothing else about it changed.
    private static String pipeline() {
        return """
                pipeline {
                    agent none

                    parameters {
                        string(name: 'BUSINESS_DATE', defaultValue: '2026-09-14')
                    }

                """
                + Pipelines.render(ESTATE.work().get(0), BLUE)
                + """

                    stages {
                        stage('EOD') {
                            steps {
                                echo 'end of day ran here'
                            }
                        }
                    }
                }
                """;
    }

    private void configure(final Controller controller) {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setOwnerId(controller.ownerId());
        configuration.setClientId(controller.clientId());
        configuration.setEnvironment(ESTATE.environment().value());
        PiplexConfiguration.useStore(store, time);
    }

    private void designate(final String owner) throws Exception {
        new Designations(store, time, PiplexObserver.NONE, ESTATE.environment())
                .designate("eod-owner", owner, "test").toCompletableFuture().get();
    }
}
