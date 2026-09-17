/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.observe;

import io.github.green4j.piplex.Generation;
import io.github.green4j.piplex.exclusive.Revocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The line is a contract for whatever aggregates it, so the shape of it is worth a test.
 */
class TextPiplexObserverTest {

    private static final RunRef RUN =
            new RunRef("eod", Generation.of("2026-09-12"), "euc1-blue", "eod#142");

    private final List<String> lines = new ArrayList<>();
    private final TextPiplexObserver observer = new TextPiplexObserver(lines::add);

    @Test
    void writesOneFieldPerThingWorthKnowing() {
        observer.admitted(RUN, 47L);

        assertEquals("ADMITTED key=eod generation=2026-09-12 owner=euc1-blue run=eod#142 fencingToken=47",
                lines.get(0));
    }

    @Test
    void spellsTheKeyTheSameWayOnEveryLine() {
        observer.milestonePublished(RUN);
        observer.milestoneReached("data/euc1", Generation.of("2026-09-12"));

        // The correlation key is what an aggregator groups on, so it is one field name everywhere: the
        // run which published and the run which was waiting have to gather under the same thing.
        assertEquals("MILESTONE_PUBLISHED key=eod generation=2026-09-12 owner=euc1-blue run=eod#142",
                lines.get(0));
        assertEquals("MILESTONE_REACHED key=data/euc1 reached=2026-09-12", lines.get(1));
    }

    @Test
    void leavesOutAFieldWithNothingInIt() {
        observer.revoked(RUN, new Revocation(Revocation.Reason.LEASE_LOST, null));

        // Absent, not "newOwner=null": an aggregator can filter on a field that is not there, while a
        // literal null is a value it has to learn to ignore.
        assertEquals("REVOKED key=eod generation=2026-09-12 owner=euc1-blue run=eod#142 "
                + "reason=LEASE_LOST", lines.get(0));
    }

    static List<Arguments> reasons() {
        return List.of(
                arguments("INC-4471 migrating the cluster", "\"INC-4471 migrating the cluster\""),
                arguments("she said \"later\"", "\"she said \\\"later\\\"\""),
                arguments("INC-4471\nsee the runbook\r\n", "\"INC-4471\\nsee the runbook\\r\\n\""),
                arguments("INC-4471\tmigrating", "\"INC-4471\\tmigrating\""));
    }

    // A reason is typed by an operator, and an aggregator splits on whitespace: unquoted, a space or a
    // tab ends the field, and a line break starts an event of its own. One event, one line.
    @ParameterizedTest
    @MethodSource("reasons")
    void keepsAReasonOneValueOnOneLine(final String typed, final String written) {
        observer.disabled(RUN, typed);

        assertEquals(List.of("DISABLED key=eod generation=2026-09-12 owner=euc1-blue run=eod#142 reason="
                + written), lines);
    }
}
