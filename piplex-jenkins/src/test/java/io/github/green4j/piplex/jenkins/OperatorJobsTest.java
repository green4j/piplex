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
import io.github.green4j.piplex.exclusive.Designation;
import io.github.green4j.piplex.exclusive.Designations;
import io.github.green4j.piplex.init.Estate;
import io.github.green4j.piplex.init.Estate.Controller;
import io.github.green4j.piplex.init.Estate.Grants;
import io.github.green4j.piplex.init.Estate.Operator;
import io.github.green4j.piplex.init.Estate.Transport;
import io.github.green4j.piplex.init.Jobs;
import io.github.green4j.piplex.milestone.Milestone;
import io.github.green4j.piplex.milestone.Milestones;
import io.github.green4j.piplex.observe.PiplexObserver;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.store.memory.InMemoryCoordinationStore;
import io.github.green4j.piplex.switches.Switch;
import io.github.green4j.piplex.switches.Switches;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the operator jobs a deployment installs, generated the way a deployment generates them.
 *
 * <p>Not copies of them, and not the templates either: {@link Jobs} fills the shipped templates in
 * from an estate, and what it produces is what runs here. A runbook is only a runbook while it still
 * works, and the two ways one stops working are that a step it calls changes under it, or that the
 * generator fills it in wrongly. Both are noticed here, or during an incident by somebody who needed
 * it to work.
 */
@WithJenkins
class OperatorJobsTest {

    private static final String ENVIRONMENT = "prod";
    private static final String SWITCH_KEY = "eod-switch";
    private static final String DESIGNATION_KEY = "eod-owner";
    private static final String WORK_KEY = "eod";
    private static final String MILESTONE_KEY = "data/euc1";

    /** Two controllers and one nightly round, which is the estate every chapter of the docs uses. */
    private static final Estate ESTATE = new Estate(Environment.of(ENVIRONMENT),
            List.of(new Controller("euc1-blue", "piplex-euc1-blue"),
                    new Controller("euc1-green", "piplex-euc1-green")),
            "n1=10.0.0.11:7101", Transport.ALLOWALL, Estate.SECRETS, Operator.SHARED,
            List.of(Estate.Work.of(WORK_KEY, SWITCH_KEY, DESIGNATION_KEY, null, MILESTONE_KEY)),
            Grants.PER_KEY);

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
    void handsWorkOver(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");

        final WorkflowRun run = run(jenkins, "handover.groovy",
                "NEW_OWNER", "euc1-green", "REASON", "INC-4821");

        jenkins.assertBuildStatus(Result.SUCCESS, run);
        jenkins.assertLogContains("'eod-owner' now designates 'euc1-green'", run);
        assertNotNull(read(Designations.keyOf(Environment.of(ENVIRONMENT), DESIGNATION_KEY)));
    }

    @Test
    void saysSoWhenTheWorkStillCannotRunWhereItWasSent(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");
        // euc1-green is drained: the handover will land, and the work still will not run there.
        new Switches(store, time, PiplexObserver.NONE, Environment.of(ENVIRONMENT))
                .disable(Switches.ownerKey(SWITCH_KEY, "euc1-green"), "patching")
                .toCompletableFuture().get();

        final WorkflowRun run = run(jenkins, "handover.groovy",
                "NEW_OWNER", "euc1-green", "REASON", "INC-4830");

        // Not green. The designation is half the answer; whether the work can run where it was sent
        // is the half somebody reading a build result at two in the morning has to be told.
        jenkins.assertBuildStatus(Result.UNSTABLE, run);
        jenkins.assertLogContains("'eod-owner' now designates 'euc1-green'", run);
        jenkins.assertLogContains("the new owner is still blocked", run);
        jenkins.assertLogContains("'euc1-green' is drained", run);
    }

    @Test
    void refusesAnActionItWasNotGiven(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");
        final String key = Switches.keyOf(Environment.of(ENVIRONMENT), SWITCH_KEY);

        // The choice parameter constrains the form, not a build started through the API or by another
        // job. Read with a fallback, every value this job was ever given that was not 'resume' --
        // a null, a typo, the wrong case -- meant stop the work on every controller.
        final WorkflowRun typo = run(jenkins, "stop-work.groovy", "ACTION", "Resume");
        jenkins.assertBuildStatus(Result.FAILURE, typo);
        jenkins.assertLogContains("ACTION is 'Resume'", typo);
        assertNull(read(key), "And nothing was written to the switch on the way");

        final WorkflowRun drainTypo = runAs(jenkins, "drain-typo", "drain-controller.groovy",
                "ACTION", "");
        jenkins.assertBuildStatus(Result.FAILURE, drainTypo);
        jenkins.assertLogContains("It must be 'drain' or 'restore'", drainTypo);
    }

    @Test
    void stopsTheWorkEverywhereAndResumesIt(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");
        final String key = Switches.keyOf(Environment.of(ENVIRONMENT), SWITCH_KEY);

        final WorkflowRun stopped = run(jenkins, "stop-work.groovy",
                "ACTION", "stop", "REASON", "bad upstream data");
        jenkins.assertBuildStatus(Result.SUCCESS, stopped);
        jenkins.assertLogContains("Nothing it guards runs anywhere", stopped);
        assertFalse(Switch.parse(read(key)).enabled(), "The switch should be off");

        final WorkflowRun resumed = runAs(jenkins, "stop-work-resume", "stop-work.groovy",
                "ACTION", "resume");
        jenkins.assertBuildStatus(Result.SUCCESS, resumed);
        assertTrue(Switch.parse(read(key)).enabled(), "The switch should be on again");
    }

    @Test
    void drainsThisControllerAndPutsItBack(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");

        final WorkflowRun drained = run(jenkins, "drain-controller.groovy",
                "ACTION", "drain", "DRAIN_TIMEOUT", "30s");
        jenkins.assertBuildStatus(Result.SUCCESS, drained);
        jenkins.assertLogContains("is drained and quiet; maintenance can start", drained);

        final WorkflowRun restored = runAs(jenkins, "drain-restore", "drain-controller.groovy",
                "ACTION", "restore");
        jenkins.assertBuildStatus(Result.SUCCESS, restored);
        jenkins.assertLogContains("takes work again", restored);
    }

    @Test
    void refusesToAnnounceWorkNobodyConfirmed(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");

        // The one mistake in this set that cannot be taken back, so the job stops rather than asks.
        final WorkflowRun run = run(jenkins, "announce-completion.groovy",
                "GENERATION", "2026-09-14");

        jenkins.assertBuildStatus(Result.FAILURE, run);
        jenkins.assertLogContains("confirm the data exists before announcing it", run);
    }

    @Test
    void announcesACompletionTheProducerNeverDid(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");

        final WorkflowRun run = run(jenkins, "announce-completion.groovy",
                "GENERATION", "2026-09-14", "DATA_CONFIRMED", "true");

        jenkins.assertBuildStatus(Result.SUCCESS, run);
        jenkins.assertLogContains("'data/euc1' is at '2026-09-14'", run);
        assertEquals("2026-09-14", Milestone.parse(
                read(Milestones.keyOf(Environment.of(ENVIRONMENT), "data/euc1")))
                .generation().value());
    }

    @Test
    void diagnosesAKeyThatWillNotParseInsteadOfFailingOnIt(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");
        corrupt(Designations.keyOf(Environment.of(ENVIRONMENT), DESIGNATION_KEY));

        final WorkflowRun run = run(jenkins, "inspect.groovy", "GENERATION", "");

        // The case the step exists for is the one it must survive: the reading goes on, the broken
        // key is named, and so is the job that puts it back.
        jenkins.assertBuildStatus(Result.SUCCESS, run);
        jenkins.assertLogContains("designated/eod-owner = UNREADABLE", run);
        jenkins.assertLogContains("fix: run 'handover'", run);
        jenkins.assertLogContains("OVERWRITE_UNREADABLE=true", run);
        // The guards after the broken one were still read, which is the whole point of surviving it.
        jenkins.assertLogContains("enabled/eod-switch = enabled", run);
    }

    @Test
    void repairsAKeyThatWillNotParseThroughTheJobThatOwnsIt(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");
        final String key = Designations.keyOf(Environment.of(ENVIRONMENT), DESIGNATION_KEY);
        corrupt(key);

        final WorkflowRun run = run(jenkins, "handover.groovy",
                "NEW_OWNER", "euc1-green", "REASON", "INC-4821", "OVERWRITE_UNREADABLE", "true");

        jenkins.assertBuildStatus(Result.SUCCESS, run);
        jenkins.assertLogContains("piplex: REPAIRING key=eod-owner", run);
        assertEquals("euc1-green", Designation.parse(read(key)).owner());
    }

    @Test
    void doesNotLetARepairPassForAnOrdinaryWrite(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");

        // Nothing is corrupt, so the flag must change neither the outcome nor what the log calls it:
        // an operator reading back afterwards has to be able to tell the two acts apart.
        final WorkflowRun run = run(jenkins, "handover.groovy",
                "NEW_OWNER", "euc1-green", "REASON", "planned move");

        jenkins.assertBuildStatus(Result.SUCCESS, run);
        jenkins.assertLogContains("piplex: DESIGNATING key=eod-owner", run);
        jenkins.assertLogNotContains("REPAIRING", run);
    }

    @Test
    void saysWhyTheWorkIsNotRunning(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");
        run(jenkins, "stop-work.groovy", "ACTION", "stop", "REASON", "bad upstream data");

        final WorkflowRun run = run(jenkins, "inspect.groovy");

        jenkins.assertBuildStatus(Result.SUCCESS, run);
        jenkins.assertLogContains("'eod-switch' is disabled ('bad upstream data')", run);
        assertNotNull(run.getDescription());
    }

    private void configure(final String ownerId) {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setOwnerId(ownerId);
        PiplexConfiguration.useStore(store, time);
    }

    private String read(final String key) throws Exception {
        return store.get(key).toCompletableFuture().get().value();
    }

    /**
     * Puts something piplex did not write where a record belongs -- a hand-edited key, or a restore
     * from a store that held another format.
     *
     * @param key the key to make unreadable
     * @throws Exception if the store will not take it
     */
    private void corrupt(final String key) throws Exception {
        store.compareAndSet(key, CoordinationStore.INITIAL_VERSION, "{\"owner\"")
                .toCompletableFuture().get();
    }

    // A drain is a question about the machine, not about one piece of work: the controller is being
    // patched, so everything running on it has to stop. An estate whose work names two switches is
    // therefore two disables under this owner, and a job that wrote one would report a controller
    // quiet while the other half of the night ran on.
    @Test
    void drainsEverySwitchTheEstateKnows(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");
        final Estate two = new Estate(Environment.of(ENVIRONMENT), ESTATE.controllers(),
                ESTATE.nodes(), Transport.ALLOWALL, Estate.SECRETS, Operator.SHARED,
                List.of(Estate.Work.of(WORK_KEY, SWITCH_KEY, DESIGNATION_KEY, null, MILESTONE_KEY),
                        Estate.Work.of("ref-data", "ref-switch", "ref-owner", null, null)),
                Grants.PER_KEY);

        final WorkflowRun drained = runFor(two, jenkins, "drain-two", "drain-controller.groovy",
                "ACTION", "drain", "DRAIN_TIMEOUT", "30s");

        jenkins.assertBuildStatus(Result.SUCCESS, drained);
        for (final String key : List.of(SWITCH_KEY, "ref-switch")) {
            final String drain = Switches.keyOf(Environment.of(ENVIRONMENT),
                    Switches.ownerKey(key, "euc1-blue"));
            final String written = read(drain);
            assertNotNull(written,
                    "'" + key + "' was never written for euc1-blue, so the controller is not drained");
            assertFalse(Switch.parse(written).enabled(), "'" + key + "' is still on for euc1-blue");
        }
        // And in that order: every switch off, and only then anything waited for. Writing one and
        // waiting on it before writing the next leaves the work the next one guards free to start
        // here for the whole of that wait -- which is the wait somebody is sitting through before
        // touching the machine. So the last switch written must precede the first DRAINING.
        final String log = drained.getLog();
        assertTrue(log.indexOf("SWITCHING key=" + Switches.ownerKey("ref-switch", "euc1-blue"))
                        < log.indexOf("piplex: DRAINING"),
                "Every switch has to be off before anything is waited for:\n" + log);
    }

    @Test
    void refusesAKeyItWasNotGeneratedWith(final JenkinsRule jenkins) throws Exception {
        configure("euc1-blue");

        // The estate is in the definition, and the parameter chooses within it. A build started
        // through the API or by another job carries whatever it was given, so the choice constrains
        // the form and nothing else -- and a work this job does not know is a key nobody decided on.
        final WorkflowRun announced = runAs(jenkins, "announce-unknown", "announce-completion.groovy",
                "WORK", "new-day", "GENERATION", "2026-09-14", "DATA_CONFIRMED", "true");
        jenkins.assertBuildStatus(Result.FAILURE, announced);
        jenkins.assertLogContains("WORK is 'new-day'", announced);
        assertNull(read(Milestones.keyOf(Environment.of(ENVIRONMENT), MILESTONE_KEY)),
                "And nothing was published on the way");

        final WorkflowRun stopped = runAs(jenkins, "stop-unknown", "stop-work.groovy",
                "ACTION", "stop", "SWITCH_KEY", "newday-switch");
        jenkins.assertBuildStatus(Result.FAILURE, stopped);
        jenkins.assertLogContains("which no work in this estate consults", stopped);
        assertNull(read(Switches.keyOf(Environment.of(ENVIRONMENT), SWITCH_KEY)),
                "And the work this controller does run was not stopped on the way");

        final WorkflowRun sent = runAs(jenkins, "handover-nowhere", "handover.groovy",
                "WORK", "eod", "NEW_OWNER", "euc1-red");
        jenkins.assertBuildStatus(Result.FAILURE, sent);
        jenkins.assertLogContains("NEW_OWNER is 'euc1-red'", sent);
        assertNull(read(Designations.keyOf(Environment.of(ENVIRONMENT), DESIGNATION_KEY)),
                "And the work was not sent to a controller that does not exist");
    }

    // What a release does to somebody who unpacked it and pressed Build. These files are templates:
    // the estate block is empty until piplex-init fills it in, and until then the job knows no key
    // at all. It has to say so and stop, because the alternative is a plausible sample name -- which
    // is a real key on somebody's cluster.
    @ParameterizedTest
    @CsvSource({
        "stop-work.groovy",
        "drain-controller.groovy",
        "handover.groovy",
        "announce-completion.groovy",
        "inspect.groovy",
    })
    void refusesATemplateNobodyGenerated(final String job, final JenkinsRule jenkins)
            throws Exception {
        configure("euc1-blue");

        final WorkflowJob project = jenkins.createProject(
                WorkflowJob.class, "ungenerated-" + job.replace(".groovy", ""));
        project.setDefinition(new CpsFlowDefinition(Jobs.template(job), true));
        // No ParametersAction at all: the job's own defaults, which is what pressing Build gives.
        final WorkflowRun run = project.scheduleBuild2(0).get();

        jenkins.assertBuildStatus(Result.FAILURE, run);
        jenkins.assertLogContains("this job has not been generated", run);
        assertNull(read(Switches.keyOf(Environment.of(ENVIRONMENT), SWITCH_KEY)),
                "An ungenerated job must write nothing on its way to being refused");
        assertNull(read(Designations.keyOf(Environment.of(ENVIRONMENT), DESIGNATION_KEY)));
        assertNull(read(Milestones.keyOf(Environment.of(ENVIRONMENT), MILESTONE_KEY)));
    }

    private WorkflowRun run(final JenkinsRule jenkins, final String job, final String... parameters)
            throws Exception {
        return runAs(jenkins, job.replace(".groovy", ""), job, parameters);
    }

    private WorkflowRun runAs(final JenkinsRule jenkins, final String name, final String job,
                              final String... parameters) throws Exception {
        return runFor(ESTATE, jenkins, name, job, parameters);
    }

    private WorkflowRun runFor(final Estate estate, final JenkinsRule jenkins, final String name,
                               final String job, final String... parameters) throws Exception {
        final WorkflowJob project = jenkins.createProject(WorkflowJob.class, name);
        project.setDefinition(new CpsFlowDefinition(
                Jobs.render(estate, estate.controllers().get(0), job), true));
        final Map<String, String> given = new LinkedHashMap<>(pressingBuild(job));
        for (int i = 0; i < parameters.length; i += 2) {
            given.put(parameters[i], parameters[i + 1]);
        }
        final List<StringParameterValue> values = new ArrayList<>();
        given.forEach((of, value) -> values.add(new StringParameterValue(of, value)));
        return project.scheduleBuild2(0, new ParametersAction(new ArrayList<>(values))).get();
    }

    /**
     * What the form offers before anybody touches it: the first entry of each choice.
     *
     * <p>Supplied here rather than left out, because a Jenkins job learns its parameters from a
     * first build and these are scheduled straight away. Every test then names only what it varies.
     *
     * @param job the job being run
     * @return the values a freshly opened form would submit
     */
    private static Map<String, String> pressingBuild(final String job) {
        final Map<String, String> values = new LinkedHashMap<>();
        switch (job) {
            case "handover.groovy" -> {
                values.put("WORK", WORK_KEY);
                values.put("NEW_OWNER", "euc1-blue");
            }
            case "stop-work.groovy" -> {
                values.put("ACTION", "stop");
                values.put("SWITCH_KEY", SWITCH_KEY);
            }
            case "drain-controller.groovy" -> values.put("ACTION", "drain");
            case "announce-completion.groovy", "inspect.groovy" -> values.put("WORK", WORK_KEY);
            default -> throw new IllegalArgumentException("No such shipped job: " + job);
        }
        return values;
    }
}
