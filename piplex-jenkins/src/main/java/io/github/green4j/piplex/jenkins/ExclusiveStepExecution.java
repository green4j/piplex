/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import hudson.AbortException;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.github.green4j.piplex.Generation;
import io.github.green4j.piplex.exclusive.Admitted;
import io.github.green4j.piplex.exclusive.ExclusiveRequest;
import org.jenkinsci.plugins.workflow.steps.BodyExecution;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Asks to run, then holds the answer for as long as the body does.
 *
 * <p>It never occupies an executor. {@link #start()} returns {@code false}, so a controller which is not
 * the one that has to run parks for four hours without holding a node, an executor or a thread -- there
 * is a {@code FlowExecution} in memory and nothing else. That is what makes it reasonable to put the
 * same schedule on every controller and let them sort it out.
 *
 * <p>Everything it keeps across a restart is a string. The live half -- the admission, its renewal
 * timer, its watches -- cannot be serialised and is not: after a controller restart
 * {@link #onResume()} asks again, from scratch. That works because asking is idempotent, and it is the
 * same reason the core compares state instead of counting events.
 *
 * <p>The body is the exception, and the one thing here that must not be redone. Jenkins wrote its
 * program state down and replays it from where it stopped, so a resume that also started a body would
 * run the guarded work twice on the same controller. What a resume owes a body already running is the
 * ownership it is running under -- retaken at once, or the body stopped, never waited for: a run
 * parked for a handover while its own work is under way is the very thing this step exists to prevent.
 */
final class ExclusiveStepExecution extends StepExecution {

    private static final long serialVersionUID = 1L;
    private static final long RELEASE_TIMEOUT_SECONDS = 15L;

    private final String key;
    private final String designatedBy;
    private final String generation;
    private final String completedWhen;
    private final String enabledBy;
    private final String lease;
    private final String renewEvery;
    private final String renewalGrace;
    private final String handoverWait;

    private transient volatile Admitted admitted;
    private BodyExecution body;
    // Both guarded by this: a revocation racing the body's start must not read a field not yet assigned.
    private transient Throwable revoked;

    ExclusiveStepExecution(final StepContext context, final PiplexExclusiveStep step) {
        super(context);
        this.key = step.getKey();
        this.designatedBy = step.getDesignatedBy();
        this.generation = step.getGeneration();
        this.completedWhen = step.getCompletedWhen();
        this.enabledBy = step.getEnabledBy();
        this.lease = step.getLease();
        this.renewEvery = step.getRenewEvery();
        this.renewalGrace = step.getRenewalGrace();
        this.handoverWait = step.getHandoverWait();
    }

    @Override
    public boolean start() throws Exception {
        if (!getContext().hasBody()) {
            throw new AbortException(
                    "piplexExclusive needs a block to guard. Use it in options { } or wrap the stage's "
                            + "steps in it -- without a body there is nothing for it to stop.");
        }
        ask();
        return false;
    }

    @Override
    public void onResume() {
        // The controller restarted. Whatever was held is gone -- the lease was not renewed while it was
        // down, and may well have been taken by somebody else -- so the honest thing is to ask again
        // rather than carry on as though nothing happened. The body, if there was one, is coming back
        // on its own; see admit.
        admitted = null;
        try {
            ask();
        } catch (final Exception failed) {
            getContext().onFailure(failed);
        }
    }

    @Override
    public void stop(final Throwable cause) throws Exception {
        // Nothing here waits for the body to notice: cancelling is asynchronous, and the callback it
        // ends in is what gives the ownership back and completes the step.
        refuse(cause);
    }

    private void ask() throws Exception {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        final TaskListener listener = getContext().get(TaskListener.class);
        // A body that is already there is one Jenkins is resuming after a restart. Everything about this
        // attempt is different because of it: the work is under way, so there is nothing to wait for and
        // nothing to start.
        final boolean resuming = body != null;
        final ExclusiveRequest request = request(configuration, resuming);
        configuration.piplexFor(listener).runs().begin(request)
                .whenComplete((admission, error) -> {
                    if (error != null) {
                        refuse(error);
                    } else if (admission instanceof Admitted granted) {
                        admit(granted);
                    } else {
                        refuse(resuming
                                ? PiplexInterruption.lostOnResume(key, admission)
                                : PiplexInterruption.of(key, admission));
                    }
                });
    }

    private ExclusiveRequest request(final PiplexConfiguration configuration, final boolean resuming)
            throws Exception {
        final Run<?, ?> run = getContext().get(Run.class);
        final ExclusiveRequest.Builder builder = ExclusiveRequest.builder(key)
                .ownedBy(configuration.requireOwnerId())
                // The build's own name, which is what a reader of two logs needs to tell them apart, and
                // what makes a second run on this controller lose the lease rather than share it.
                .runId(run.getFullDisplayName())
                .designatedBy(designatedBy)
                .completedWhen(completedWhen)
                .enabledBy(enabledBy)
                .lease(Durations.parse(lease, ExclusiveRequest.DEFAULT_LEASE, "lease"))
                // Parking is for a candidate with nothing running. A run whose body is resuming cannot
                // park: it would be doing the work for as long as it waited for permission to do it.
                .handoverWait(resuming
                        ? Duration.ZERO
                        : Durations.parse(handoverWait, Duration.ZERO, "handoverWait"));
        if (generation != null && !generation.isBlank()) {
            builder.generation(Generation.of(generation.trim()));
        }
        if (renewEvery != null && !renewEvery.isBlank()) {
            builder.renewEvery(Durations.parse(renewEvery, null, "renewEvery"));
        }
        if (renewalGrace != null && !renewalGrace.isBlank()) {
            builder.renewalGrace(Durations.parse(renewalGrace, null, "renewalGrace"));
        }
        return builder.build();
    }

    private void admit(final Admitted granted) {
        admitted = granted;
        // Registered before the body starts, so a designation that changes in the same second as the
        // admission still stops it. The listener runs on a piplex thread, and all it does is ask Jenkins
        // to cancel -- the stopping itself happens where Jenkins does it.
        // Ownership can be gone before this line: the watches are armed before the admission is handed
        // over, so the listener may fire here, with no body to cancel. Remembered, not dropped.
        granted.onRevoked(revocation -> cancel(PiplexInterruption.revoked(key, revocation)));
        final Throwable alreadyGone;
        synchronized (this) {
            if (body != null) {
                // Resuming after a restart. The body is Jenkins' to bring back -- its program state was
                // written down and is replayed from where it stopped -- and starting another one here
                // would run the guarded work twice on the same controller. All this attempt owed the
                // resumed body was the ownership it is running under, and that is now retaken.
                return;
            }
            alreadyGone = revoked;
            if (alreadyGone == null) {
                // Under the monitor, so a revocation racing the start waits for the body to exist.
                body = getContext().newBodyInvoker()
                        .withCallback(new ReleaseOnFinish(this))
                        .start();
            }
        }
        if (alreadyGone != null) {
            refuse(alreadyGone);
        }
    }

    /**
     * Stops the body, or remembers that it must not be started.
     *
     * @param cause why it may not run
     */
    private void cancel(final Throwable cause) {
        final BodyExecution running;
        synchronized (this) {
            running = body;
            if (running == null) {
                revoked = cause;
                return;
            }
        }
        // Outside the monitor: cancelling ends in a callback which comes back through this object.
        running.cancel(cause);
    }

    /**
     * Ends the step, and the body with it when there is one.
     *
     * @param cause why it may not run
     */
    private void refuse(final Throwable cause) {
        final BodyExecution running;
        synchronized (this) {
            running = body;
        }
        if (running != null) {
            // Cancelling is what ends the step here: the body's callback releases the ownership and
            // completes the context, and doing either from here would be a second answer to the same
            // question.
            running.cancel(cause);
            return;
        }
        release();
        getContext().onFailure(cause);
    }

    private void release() {
        final Admitted granted = admitted;
        admitted = null;
        if (granted == null) {
            return;
        }
        try {
            // Waited for rather than fired and forgotten: until the lease is gone, the controller taking
            // over has to wait for it to lapse, and that wait is the handover. Bounded, because a store
            // that has stopped answering must not also stop the build from finishing -- the lease lapses
            // on its own soon enough.
            granted.release().toCompletableFuture().get(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (final TimeoutException | ExecutionException failed) {
            // Nothing to do about it here, and nothing that needs doing: an unreleased lease lapses.
        }
    }

    /**
     * Gives the ownership back when the body is done, whichever way it went.
     */
    private static final class ReleaseOnFinish extends BodyExecutionCallback.TailCall {

        private static final long serialVersionUID = 1L;

        private final ExclusiveStepExecution execution;

        ReleaseOnFinish(final ExclusiveStepExecution execution) {
            this.execution = execution;
        }

        @Override
        protected void finished(final StepContext context) {
            execution.release();
        }
    }
}
