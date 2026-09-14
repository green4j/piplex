/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex;

import io.github.green4j.piplex.exclusive.Designations;
import io.github.green4j.piplex.exclusive.ExclusiveRuns;
import io.github.green4j.piplex.milestone.Milestones;
import io.github.green4j.piplex.observe.PiplexObserver;
import io.github.green4j.piplex.store.CoordinationStore;
import io.github.green4j.piplex.switches.Switches;

import java.util.Objects;

/**
 * The primitives, built once over one store.
 *
 * <p>Each of them can be constructed on its own and sometimes should be. This exists because in a host
 * -- a Jenkins controller, say -- they are not independent: they read each other's keys, and they are
 * only correct if they agree about which store, which time source and which observer they use. Assembled
 * here, disagreeing is not something a caller can do by accident.
 *
 * <p>It owns nothing. The store, the scheduler behind the time source and the observer's sink were
 * created by whoever passed them in, and are closed by them -- typically once, when the host shuts down.
 *
 * <p>Construction stays explicit: nothing here is discovered, and the store is the argument that decides
 * everything else.
 */
public final class Piplex {

    private final CoordinationStore store;
    private final ExclusiveRuns runs;
    private final Milestones milestones;
    private final Designations designations;
    private final Switches switches;

    /**
     * @param store    where all of it is kept
     * @param time     where time comes from
     * @param observer told about every decision
     */
    public Piplex(final CoordinationStore store,
                  final TimeSource time,
                  final PiplexObserver observer) {
        this.store = Objects.requireNonNull(store, "store");
        this.milestones = new Milestones(store, time, observer);
        this.runs = new ExclusiveRuns(store, time, observer);
        this.designations = new Designations(store, time);
        this.switches = new Switches(store);
    }

    /**
     * @return the store everything here reads and writes
     */
    public CoordinationStore store() {
        return store;
    }

    /**
     * @return asking to run, and holding the right to
     */
    public ExclusiveRuns runs() {
        return runs;
    }

    /**
     * @return publishing how far the work got, and waiting for it
     */
    public Milestones milestones() {
        return milestones;
    }

    /**
     * @return the operator's side of who may run the work
     */
    public Designations designations() {
        return designations;
    }

    /**
     * @return the operator's side of whether the work may run at all
     */
    public Switches switches() {
        return switches;
    }
}
