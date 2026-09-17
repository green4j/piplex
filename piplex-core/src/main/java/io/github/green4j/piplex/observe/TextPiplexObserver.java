/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.observe;

import io.github.green4j.piplex.Generation;
import io.github.green4j.piplex.exclusive.Revocation;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * Writes the event vocabulary as one line per decision, in a shape an aggregator can group.
 *
 * <p>Every line carries {@code key}, and every line about a run carries {@code generation} with it:
 * that pair is the correlation key, and one day's attempt at one key gathers under it across every
 * controller -- the one which ran and the ones which waited -- with nothing to join on. A line about
 * waiting for a milestone names the generation it wanted rather than one it is at, so it says
 * {@code wanted} and {@code reached}; the key is spelt the same way everywhere.
 *
 * <pre>
 * ADMITTED key=eod generation=2026-09-12 owner=euc1-blue run=eod#142 fencingToken=47
 * PARKED   key=eod generation=2026-09-12 owner=eus1-blue run=eod#88 waitingOn=euc1-blue remaining=PT4H
 * </pre>
 */
public final class TextPiplexObserver implements PiplexObserver {

    private final Consumer<String> sink;

    /**
     * @param sink where lines go -- a logger, {@code System.out}, anything
     */
    public TextPiplexObserver(final Consumer<String> sink) {
        this.sink = sink;
    }

    @Override
    public void admitted(final RunRef run, final long fencingToken) {
        emit(line("ADMITTED", run).put("fencingToken", fencingToken));
    }

    @Override
    public void notDesignated(final RunRef run, final String currentOwner) {
        emit(line("NOT_DESIGNATED", run).put("currentOwner", currentOwner));
    }

    @Override
    public void heldByOther(final RunRef run, final String heldBy) {
        emit(line("HELD_BY_OTHER", run).put("heldBy", heldBy));
    }

    @Override
    public void contended(final RunRef run) {
        emit(line("CONTENDED", run));
    }

    @Override
    public void alreadyCompleted(final RunRef run, final Generation reached) {
        emit(line("ALREADY_COMPLETED", run).put("reached", reached));
    }

    @Override
    public void disabled(final RunRef run, final String reason) {
        emit(line("DISABLED", run).put("reason", reason));
    }

    @Override
    public void guardUnreadable(final RunRef run, final String key, final String detail) {
        emit(line("GUARD_UNREADABLE", run).put("guard", key).put("detail", detail));
    }

    @Override
    public void parked(final RunRef run, final String waitingOn, final Duration remaining) {
        emit(line("PARKED", run).put("waitingOn", waitingOn).put("remaining", remaining));
    }

    @Override
    public void revoked(final RunRef run, final Revocation cause) {
        // detail as well as reason: a run stopped by a switch carries the operator's own words there,
        // and a run stopped by a hand-edited key carries which key. Neither is in the reason name.
        emit(line("REVOKED", run)
                .put("reason", cause.reason())
                .put("newOwner", cause.newOwner())
                .put("detail", cause.detail()));
    }

    @Override
    public void released(final RunRef run) {
        emit(line("RELEASED", run));
    }

    @Override
    public void milestonePublished(final RunRef run) {
        emit(line("MILESTONE_PUBLISHED", run));
    }

    @Override
    public void milestoneReached(final String milestone, final Generation reached) {
        emit(new Line("MILESTONE_REACHED").put("key", milestone).put("reached", reached));
    }

    @Override
    public void milestoneWaiting(final String milestone,
                                 final Generation wanted,
                                 final Generation reached,
                                 final Duration giveUpAfter) {
        emit(new Line("MILESTONE_WAITING")
                .put("key", milestone)
                .put("wanted", wanted)
                .put("reached", reached)
                .put("giveUpAfter", giveUpAfter));
    }

    @Override
    public void milestoneTimedOut(final String milestone,
                                  final Generation wanted,
                                  final Generation reached) {
        emit(new Line("MILESTONE_TIMED_OUT")
                .put("key", milestone)
                .put("wanted", wanted)
                .put("reached", reached));
    }

    @Override
    public void designated(final String key,
                           final String owner,
                           final String previous,
                           final String reason) {
        emit(new Line("DESIGNATED")
                .put("key", key)
                .put("owner", owner)
                .put("previous", previous)
                .put("reason", reason));
    }

    @Override
    public void switched(final String key, final boolean enabled, final String reason) {
        emit(new Line("SWITCHED").put("key", key).put("enabled", enabled).put("reason", reason));
    }

    private static Line line(final String event, final RunRef run) {
        return new Line(event)
                .put("key", run.key())
                .put("generation", run.generation())
                .put("owner", run.ownerId())
                .put("run", run.runId());
    }

    private void emit(final Line line) {
        sink.accept(line.toString());
    }

    /**
     * One line under construction.
     *
     * <p>A field with nothing in it is left out rather than written as {@code null}. The line is a
     * contract for whatever aggregates it, and "absent" is a thing an aggregator can filter on, while a
     * literal null is a value it has to learn to ignore.
     */
    private static final class Line {

        private final StringBuilder text;

        private Line(final String event) {
            this.text = new StringBuilder(event);
        }

        private Line put(final String name, final Object value) {
            if (value != null) {
                text.append(' ').append(name).append('=').append(quoted(value.toString()));
            }
            return this;
        }

        /**
         * A value with a space in it -- and a reason an operator typed is the usual one -- would end
         * the field where the aggregator splits, and everything after it would parse as a field of its
         * own. Quoted, it stays one value.
         *
         * <p>A line break in one is worse than a space and is just as easy to type: it would end the
         * line itself, and the rest of the value would reach the aggregator as an event of its own with
         * a name nobody defined. Written as an escape rather than quoted away, because "one event, one
         * line" is the whole contract here.
         *
         * @param value the value
         * @return it, quoted where it has to be
         */
        private static String quoted(final String value) {
            if (value.indexOf(' ') < 0 && value.indexOf('"') < 0 && value.indexOf('=') < 0
                    && value.indexOf('\n') < 0 && value.indexOf('\r') < 0 && value.indexOf('\t') < 0) {
                return value;
            }
            // The tab is here for the same reason the space is: an aggregator splits on whitespace, and
            // a reason pasted out of a spreadsheet or a ticket is where one comes from.
            return '"' + value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t") + '"';
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
