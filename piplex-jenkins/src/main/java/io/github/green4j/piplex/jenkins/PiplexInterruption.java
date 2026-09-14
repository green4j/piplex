/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import hudson.model.Result;
import io.github.green4j.piplex.exclusive.Admission;
import io.github.green4j.piplex.exclusive.Revocation;
import jenkins.model.CauseOfInterruption;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;

/**
 * Why a build stopped, in a form Jenkins keeps and shows.
 *
 * <p>Two things have to be right here, and the obvious way gets both wrong.
 *
 * <p>The first is the <b>result</b>. Calling {@code error()} would make every one of these a red build,
 * and three controllers a night going red for doing exactly what they were told is how a team learns to
 * ignore its own build page. "Not my region", "already done" and "switched off" are {@link Result#NOT_BUILT}
 * -- this build had nothing to do. Only losing ownership mid-run is {@link Result#ABORTED}: work started
 * and was stopped.
 *
 * <p>The second is that the reason has to <b>survive</b>. A line in the log is gone as soon as somebody
 * looks at the build a week later; a {@link CauseOfInterruption} is stored with the build and shown next
 * to it. So every one of these carries the answer to "why did this build not run", in words meant for
 * whoever is asking at three in the morning.
 */
final class PiplexInterruption {

    private PiplexInterruption() {
    }

    /**
     * @param key        what was being competed for
     * @param admission  why this run may not have it
     * @return the exception to fail the step with
     */
    static FlowInterruptedException of(final String key, final Admission admission) {
        // Not an actual interruption: nothing was running to interrupt. The flag is what keeps the
        // build from being reported as aborted by somebody.
        return new FlowInterruptedException(
                Result.NOT_BUILT, false, new NotOurs(key, describe(admission)));
    }

    /**
     * @param key   what was being held
     * @param cause why it was taken away
     * @return the exception to stop the body with
     */
    static FlowInterruptedException revoked(final String key, final Revocation cause) {
        // This one is: work was under way and was stopped.
        return new FlowInterruptedException(Result.ABORTED, true, new Revoked(key, cause));
    }

    /**
     * @param key       what was being held
     * @param admission why it may not be held again
     * @return the exception to stop the resumed body with
     */
    static FlowInterruptedException lostOnResume(final String key, final Admission admission) {
        // A restart is not a fresh start for a build whose body had already begun: the work ran, the
        // lease lapsed while the controller was down, and somebody else has it now. Aborted, like any
        // other run stopped half way, and never NOT_BUILT -- this build built something.
        return new FlowInterruptedException(
                Result.ABORTED, true, new LostOnResume(key, describe(admission)));
    }

    private static String describe(final Admission admission) {
        if (admission instanceof Admission.NotDesignated notDesignated) {
            return notDesignated.currentOwner() == null
                    ? "nobody is designated to run it yet"
                    : "'" + notDesignated.currentOwner() + "' is designated to run it";
        }
        if (admission instanceof Admission.HeldByOther held) {
            return "'" + held.heldBy() + "' is running it";
        }
        if (admission instanceof Admission.AlreadyCompleted completed) {
            return "it is already done up to " + completed.reached();
        }
        if (admission instanceof Admission.Disabled disabled) {
            return "it is switched off: " + disabled.reason();
        }
        return admission.toString();
    }

    /**
     * This build was not the one that had to run.
     */
    static final class NotOurs extends CauseOfInterruption {

        private static final long serialVersionUID = 1L;

        private final String key;
        private final String why;

        NotOurs(final String key, final String why) {
            this.key = key;
            this.why = why;
        }

        @Override
        public String getShortDescription() {
            return "piplex: did not run '" + key + "' because " + why;
        }
    }

    /**
     * This build was running and was told to stop.
     */
    static final class Revoked extends CauseOfInterruption {

        private static final long serialVersionUID = 1L;

        private final String key;
        private final String reason;
        private final String newOwner;
        private final String detail;

        Revoked(final String key, final Revocation cause) {
            this.key = key;
            this.reason = cause.reason().name();
            this.newOwner = cause.newOwner();
            this.detail = cause.detail();
        }

        @Override
        public String getShortDescription() {
            final String tail = newOwner == null ? "" : "; '" + newOwner + "' takes over";
            // The detail is what a reason name cannot carry: which key, and what is wrong with it. A
            // build stopped by a hand-edited value is unactionable without it.
            final String why = detail == null ? "" : ": " + detail;
            return "piplex: stopped running '" + key + "' (" + reason + ")" + why + tail;
        }
    }

    /**
     * This build was running before the controller restarted, and may not carry on.
     */
    static final class LostOnResume extends CauseOfInterruption {

        private static final long serialVersionUID = 1L;

        private final String key;
        private final String why;

        LostOnResume(final String key, final String why) {
            this.key = key;
            this.why = why;
        }

        @Override
        public String getShortDescription() {
            return "piplex: stopped running '" + key + "' after a restart because " + why;
        }
    }

    /**
     * A milestone did not arrive in time.
     */
    static final class MilestoneMissing extends CauseOfInterruption {

        private static final long serialVersionUID = 1L;

        private final String milestone;
        private final String wanted;
        private final String reached;

        MilestoneMissing(final String milestone, final String wanted, final String reached) {
            this.milestone = milestone;
            this.wanted = wanted;
            this.reached = reached;
        }

        @Override
        public String getShortDescription() {
            return "piplex: waited for '" + milestone + "' to reach " + wanted
                    + " but it is at " + (reached == null ? "nothing yet" : reached);
        }
    }
}
