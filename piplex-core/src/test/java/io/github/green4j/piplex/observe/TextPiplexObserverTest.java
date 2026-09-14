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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @Test
    void keepsAValueWithSpacesInItOneValue() {
        // What an operator types when switching work off, and the commonest way a key=value line stops
        // parsing: unquoted, everything after the first space reads as a field of its own.
        observer.disabled(RUN, "INC-4471 migrating the cluster");

        assertEquals("DISABLED key=eod generation=2026-09-12 owner=euc1-blue run=eod#142 "
                + "reason=\"INC-4471 migrating the cluster\"", lines.get(0));
    }

    @Test
    void escapesAQuoteRatherThanEndingTheValueWithIt() {
        observer.disabled(RUN, "she said \"later\"");

        assertEquals("DISABLED key=eod generation=2026-09-12 owner=euc1-blue run=eod#142 "
                + "reason=\"she said \\\"later\\\"\"", lines.get(0));
    }
}
