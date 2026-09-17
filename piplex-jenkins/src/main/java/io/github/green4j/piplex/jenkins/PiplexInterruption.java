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
 * and was stopped. A key nobody can read is the one that is {@link Result#FAILURE}, because it is the one
 * that is nobody's decision: skipping quietly on a value somebody broke is how a region stops running for
 * a week without anybody noticing.
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
        if (admission instanceof Admission.GuardUnreadable unreadable) {
            // Red, and deliberately the only one here that is: nothing decided that this build should
            // not run -- a value an operator has to fix says what may run cannot be worked out at all.
            return new FlowInterruptedException(
                    Result.FAILURE, false, new Unreadable(key, unreadable.detail()));
        }
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
        if (admission instanceof Admission.GuardUnreadable unreadable) {
            // Red on this side of a restart too. Which side of a restart a broken value was found on is
            // not what the person fixing it needs to be told, and an aborted build says somebody else
            // has the work -- which is the one thing that is not true here: nobody can tell who has it.
            return new FlowInterruptedException(
                    Result.FAILURE, false, new Unreadable(key, unreadable.detail()));
        }
        // A restart is not a fresh start for a build whose body had already begun: the work ran, the
        // lease lapsed while the controller was down, and somebody else has it now. Aborted, like any
        // other run stopped half way, and never NOT_BUILT -- this build built something.
        return new FlowInterruptedException(
                Result.ABORTED, true, new LostOnResume(key, describe(admission)));
    }

    /**
     * @param key   what was being held
     * @param cause why ownership could not even be asked for again
     * @return the exception to stop the resumed body with
     */
    static FlowInterruptedException unavailableOnResume(final String key, final Throwable cause) {
        // Aborted, like the other resumes: work had started, and nothing is holding the key for it. The
        // cause is kept as text only: this exception is written down with the build, and the cause may
        // not be writable.
        return new FlowInterruptedException(
                Result.ABORTED, true, new UnavailableOnResume(key, messageOf(cause)));
    }

    private static String messageOf(final Throwable cause) {
        final String message = cause.getMessage();
        return message == null ? cause.toString() : message;
    }

    /**
     * @param key     what was being held
     * @param had     the token the body was started with
     * @param nowHas  the token the ownership was retaken under
     * @return the exception to stop the resumed body with
     */
    static FlowInterruptedException fencedOut(final String key, final long had, final long nowHas) {
        // The ownership is genuinely held again, so this is not a run that lost anything -- and it is
        // still a run that must not carry on. Its body was started with the old token, has already
        // handed it to whatever it protects, and is mid-replay where nothing can be corrected. Aborted,
        // like any other run stopped half way, because work had started.
        return new FlowInterruptedException(Result.ABORTED, true, new FencedOut(key, had, nowHas));
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
        if (admission instanceof Admission.Contended) {
            return "the lease was free but another attempt's write won the race to take it; nobody is "
                    + "holding it, so running the build again should get it";
        }
        if (admission instanceof Admission.AlreadyCompleted completed) {
            return "it is already done up to " + completed.reached();
        }
        if (admission instanceof Admission.Disabled disabled) {
            return "it is switched off: " + disabled.reason();
        }
        if (admission instanceof Admission.GuardUnreadable unreadable) {
            return unreadable.detail();
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
     * A key this build turns on cannot be read, so what it permits could not be worked out.
     */
    static final class Unreadable extends CauseOfInterruption {

        private static final long serialVersionUID = 1L;

        private final String key;
        private final String detail;

        Unreadable(final String key, final String detail) {
            this.key = key;
            this.detail = detail;
        }

        @Override
        public String getShortDescription() {
            return "piplex: could not work out whether to run '" + key + "': " + detail;
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
     * This build was running before the controller restarted, and its ownership could not be asked for.
     */
    static final class UnavailableOnResume extends CauseOfInterruption {

        private static final long serialVersionUID = 1L;

        private final String key;
        private final String why;

        UnavailableOnResume(final String key, final String why) {
            this.key = key;
            this.why = why;
        }

        @Override
        public String getShortDescription() {
            return "piplex: stopped running '" + key + "' after a restart because its ownership "
                    + "could not be checked: " + why;
        }
    }

    /**
     * This build's ownership came back after a restart, and came back as a different lease.
     */
    static final class FencedOut extends CauseOfInterruption {

        private static final long serialVersionUID = 1L;

        private final String key;
        private final long had;
        private final long nowHas;

        FencedOut(final String key, final long had, final long nowHas) {
            this.key = key;
            this.had = had;
            this.nowHas = nowHas;
        }

        @Override
        public String getShortDescription() {
            // Both numbers, because what somebody has to work out is what the protected resource was
            // told by this build and what it will be told by the next one.
            return "piplex: stopped running '" + key + "' after a restart: it was admitted again, but "
                    + "under fencing token " + nowHas + " and its work is running with " + had;
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
