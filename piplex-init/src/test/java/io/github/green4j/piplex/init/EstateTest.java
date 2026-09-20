/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.init;

import io.github.green4j.piplex.Environment;
import io.github.green4j.piplex.init.Estate.Controller;
import io.github.green4j.piplex.init.Estate.Grants;
import io.github.green4j.piplex.init.Estate.Operator;
import io.github.green4j.piplex.init.Estate.Transport;
import io.github.green4j.piplex.init.Estate.Work;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an estate has to get right before anything is written from it.
 *
 * <p>These are the mistakes that cost an incident rather than a build: a grant that reaches a
 * controller it was never meant to, two machines answering to one name, a guard nobody consults.
 */
class EstateTest {

    private static final String NODES = "n1=10.0.0.11:7101,n2=10.0.0.12:7101,n3=10.0.0.13:7101";

    @Test
    void findsNothingWrongWithTheSimplestEstateThereIs() {
        assertEquals(List.of(), simplest().problems());
    }

    // The invariant the shipped ACL file states and says nothing checks. Grants match raw bytes, so
    // the per-owner drain key of 'euc1-blue' is also matched by a grant written for 'euc1-blue-2'.
    @Test
    void refusesAnOwnerIdThatIsAPrefixOfAnother() {
        final Estate estate = with(List.of(
                new Controller("euc1-blue", "piplex-euc1-blue"),
                new Controller("euc1-blue-2", "piplex-euc1-blue-2")));

        final List<String> problems = estate.problems();

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("is a prefix of"), problems.get(0));
        assertTrue(problems.get(0).contains("Rename one"), problems.get(0));
    }

    @Test
    void refusesTwoControllersAnsweringToOneName() {
        final List<String> problems = with(List.of(
                new Controller("euc1-blue", "piplex-a"),
                new Controller("euc1-blue", "piplex-b"))).problems();

        assertTrue(problems.stream().anyMatch(said -> said.contains("call themselves 'euc1-blue'")),
                problems.toString());
    }

    @Test
    void refusesAnOwnerIdThatCouldLeaveTheControllersDirectory() {
        final List<String> problems = with(List.of(
                new Controller("/tmp/not-a-controller", "piplex-a"))).problems();

        assertTrue(problems.stream().anyMatch(said -> said.contains("is not one file name")),
                problems.toString());
    }

    @Test
    void refusesTwoControllersTheAclCannotTellApart() {
        final List<String> problems = with(List.of(
                new Controller("euc1-blue", "piplex-ops"),
                new Controller("eus1-blue", "piplex-ops"))).problems();

        assertTrue(problems.stream().anyMatch(said -> said.contains("connect as 'piplex-ops'")),
                problems.toString());
    }

    // Two answers to one question. The core refuses a request carrying both, so an estate that
    // generates one has generated work that cannot run.
    @Test
    void refusesWorkThatNamesBothADesignationAndAnActiveKey() {
        final Estate estate = work(Work.of("eod", "eod-switch", "eod-owner", "/dc/active", null));

        assertTrue(estate.problems().stream().anyMatch(said -> said.contains("answer the same")),
                estate.problems().toString());
    }

    @Test
    void refusesAnActiveKeyUnderPiplexsOwnRoot() {
        final Estate estate = work(Work.of("eod", "eod-switch", null, "piplex/dc/active", null));

        assertTrue(estate.problems().stream().anyMatch(said -> said.contains("belongs to whoever")),
                estate.problems().toString());
    }

    @Test
    void refusesWorkWhenNobodyDecidesWhereItRuns() {
        final Estate estate = work(Work.of("eod", "eod-switch", null, null, null));

        assertTrue(estate.problems().stream().anyMatch(
                said -> said.contains("whoever takes the lease runs")), estate.problems().toString());
    }

    @Test
    void refusesASecondIdentityWhereTheClusterProvesNoIdentityAtAll() {
        final Estate estate = new Estate(Environment.of("prod"),
                List.of(new Controller("euc1-blue", "piplex-euc1-blue")), NODES,
                Transport.ALLOWALL, Estate.SECRETS, new Operator("piplex-ops", "piplex-ops-token"),
                List.of(Work.of("eod", "eod-switch", "eod-owner", null, "data/euc1")),
                Grants.PER_ENVIRONMENT);

        assertTrue(estate.problems().stream().anyMatch(said -> said.contains("separates nothing")),
                estate.problems().toString());
    }

    // The combination the shipped ACL files have no file for, and the reason is not written down:
    // grants add, so a blanket grant hands the controllers back the writes separating the operator
    // was meant to take away.
    @Test
    void refusesAnOperatorSeparatedInNameOnly() {
        final Estate estate = new Estate(Environment.of("prod"),
                List.of(new Controller("euc1-blue", "piplex-euc1-blue")), NODES,
                Transport.MTLS, Estate.SECRETS, new Operator("piplex-ops", "piplex-ops-token"),
                List.of(Work.of("eod", "eod-switch", "eod-owner", null, "data/euc1")),
                Grants.PER_ENVIRONMENT);

        final List<String> problems = estate.problems();

        assertTrue(problems.stream().anyMatch(said -> said.contains("nothing is separated")),
                problems.toString());
    }

    @Test
    void refusesAnEstateWithNowhereToRunAndNothingToReach() {
        final Estate nowhere = new Estate(Environment.of("prod"), List.of(), "",
                Transport.ALLOWALL, Estate.SECRETS, Operator.SHARED,
                List.of(Work.of("eod", "eod-switch", "eod-owner", null, null)),
                Grants.PER_ENVIRONMENT);

        final List<String> problems = nowhere.problems();

        assertTrue(problems.stream().anyMatch(said -> said.contains("No controllers")), problems.toString());
        assertTrue(problems.stream().anyMatch(said -> said.contains("No discas nodes")), problems.toString());
    }

    // Work nobody can stop. Every controller gets a drain job, and a drain reaches only work that
    // names a switch -- so this is the one way "the controller is empty" can be a lie.
    @Test
    void refusesWorkThatNamesNoSwitchAndSoSurvivesADrain() {
        final Estate estate = work(Work.of("eod", null, "eod-owner", null, "data/euc1"));

        assertTrue(estate.problems().stream().anyMatch(
                said -> said.contains("reported drained goes on running it")),
                estate.problems().toString());
    }

    @Test
    void refusesTwoPiecesOfWorkCompetingForOneKey() {
        final Estate estate = work(
                Work.of("eod", "eod-switch", "eod-owner", null, "data/euc1"),
                Work.of("eod", "newday-switch", "newday-owner", null, null));

        assertTrue(estate.problems().stream().anyMatch(said -> said.contains("with two sets of guards")),
                estate.problems().toString());
    }

    @Test
    void refusesAWorkKeyThatCouldLeaveThePipelinesDirectory() {
        final Estate estate = work(
                Work.of("../eod", "eod-switch", "eod-owner", null, "data/euc1"));

        assertTrue(estate.problems().stream().anyMatch(said -> said.contains("is not one file name")),
                estate.problems().toString());
    }

    // Which work is wrong has to be in the words, or an estate of a dozen says only that one of them
    // is and leaves the reader to find it.
    @Test
    void namesTheWorkEachProblemIsAbout() {
        final Estate estate = work(
                Work.of("eod", "eod-switch", "eod-owner", null, "data/euc1"),
                Work.of("new-day", "newday-switch", null, null, null));

        final List<String> problems = estate.problems();

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).startsWith("Work 'new-day' "), problems.get(0));
    }

    @Test
    void findsEverySwitchADrainHasToCover() {
        final Estate estate = work(
                Work.of("eod", "nightly", "eod-owner", null, "data/euc1"),
                Work.of("corrections", "nightly", "eod-owner", null, null),
                Work.of("ref-data", "ref-data-switch", "ref-owner", null, null));

        assertEquals(List.of("nightly", "ref-data-switch"), estate.switchKeys());
    }

    @Test
    void findsNothingWrongWithAnEstateOfSeveralWorks() {
        final Estate estate = work(
                Work.of("eod", "nightly", "eod-owner", null, "data/euc1"),
                Work.of("corrections", "nightly", "eod-owner", null, "data/corrections"));

        assertEquals(List.of(), estate.problems());
    }

    private static Estate simplest() {
        return with(List.of(new Controller("euc1-blue", "piplex-euc1-blue"),
                new Controller("euc1-green", "piplex-euc1-green")));
    }

    private static Estate with(final List<Controller> controllers) {
        return new Estate(Environment.of("prod"), controllers, NODES, Transport.ALLOWALL, Estate.SECRETS,
                Operator.SHARED,
                List.of(Work.of("eod", "eod-switch", "eod-owner", null, "data/euc1")),
                Grants.PER_KEY);
    }

    private static Estate work(final Work... work) {
        return new Estate(Environment.of("prod"),
                List.of(new Controller("euc1-blue", "piplex-euc1-blue")), NODES,
                Transport.ALLOWALL, Estate.SECRETS, Operator.SHARED, List.of(work), Grants.PER_ENVIRONMENT);
    }
}
