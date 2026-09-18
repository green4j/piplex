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
 * only correct if they agree about which store, which time source, which observer and which environment
 * they use. Assembled here, disagreeing is not something a caller can do by accident.
 *
 * <p>It owns nothing. The store, the scheduler behind the time source and the observer's sink were
 * created by whoever passed them in, and are closed by them -- typically once, when the host shuts down.
 *
 * <p>Construction stays explicit: nothing here is discovered, and the store is the argument that decides
 * everything else.
 */
public final class Piplex {

    private final CoordinationStore store;
    private final TimeSource time;
    private final PiplexObserver observer;
    private final Environment environment;

    private final ExclusiveRuns runs;
    private final Milestones milestones;
    private final Designations designations;
    private final Switches switches;

    /**
     * The primitives in {@link Environment#DEFAULT}, for a host which serves one environment and never
     * names it.
     *
     * @param store    where all of it is kept
     * @param time     where time comes from
     * @param observer told about every decision
     */
    public Piplex(final CoordinationStore store,
                  final TimeSource time,
                  final PiplexObserver observer) {
        this(store, time, observer, Environment.DEFAULT);
    }

    /**
     * @param store       where all of it is kept
     * @param time        where time comes from
     * @param observer    told about every decision
     * @param environment which set of orchestrations these primitives work on
     */
    public Piplex(final CoordinationStore store,
                  final TimeSource time,
                  final PiplexObserver observer,
                  final Environment environment) {
        this.store = Objects.requireNonNull(store, "store");
        this.time = time;
        this.observer = observer;
        this.environment = Objects.requireNonNull(environment, "environment");
        this.milestones = new Milestones(store, time, observer, environment);
        this.runs = new ExclusiveRuns(store, time, observer, environment);
        this.designations = new Designations(store, time, observer, environment);
        this.switches = new Switches(store, time, observer, environment);
    }

    /**
     * The same primitives over the same store, working on another environment.
     *
     * <p>For a host which serves several: one controller running the same job for production and for
     * uat needs one of these per environment, and they must not be the same one. Cheap and not cached
     * -- four small objects -- so it is meant to be called where the environment is known, once per
     * piece of work, rather than kept in a map somewhere.
     *
     * @param other which set of orchestrations to work on instead
     * @return the primitives, bound to it
     */
    public Piplex in(final Environment other) {
        return other.equals(environment) ? this : new Piplex(store, time, observer, other);
    }

    /**
     * @return which set of orchestrations these primitives work on
     */
    public Environment environment() {
        return environment;
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
