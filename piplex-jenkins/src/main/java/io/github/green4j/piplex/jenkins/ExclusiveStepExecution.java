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
import io.github.green4j.piplex.TimeSource;
import io.github.green4j.piplex.exclusive.Admission;
import io.github.green4j.piplex.exclusive.Admitted;
import io.github.green4j.piplex.exclusive.ExclusiveRequest;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.BodyExecution;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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

    /**
     * The fields below this line are written down with a running build and read back by whatever
     * version of the plugin the controller comes back on. Adding one, renaming one or changing what one
     * means is therefore an upgrade question and not a refactor: a step written by the old version is
     * handed the new field's default, and {@link #onResume()} is where that is caught.
     *
     * <p>{@code bodyEnded}, {@code bodyFailure} and {@code waitedNanos} are the fields added since, and
     * they need nothing caught: a step written down before they existed comes back as a body that has
     * not ended, which is what every step inside the block was, and as a park that has not waited yet.
     * The version below therefore stays where it is.
     *
     * <p>{@link #STATE_VERSION} is the version that says what the fields mean. Raise it when a change
     * needs more than a default to read the old state, teach {@link #onResume()} the old one, and
     * record the new version's fixtures ({@code StateFixturesTest}), which checks both.
     */
    private static final long serialVersionUID = 1L;
    /** What the fields written down mean. {@code 0} is state written before this was kept. */
    static final int STATE_VERSION = 1;
    private static final long RELEASE_TIMEOUT_SECONDS = 15L;

    /**
     * The admissions in flight on this controller, by the name their bodies know them under.
     *
     * <p>A body's context is written down and replayed, so it cannot hold the admission itself -- a
     * lease being renewed, a timer, two watches, none of it serialisable. It holds the name, and this
     * is what a name means right now. Static and in memory only: a controller restart empties it, and
     * a resumed step puts itself back in when it has been admitted again.
     */
    private static final Map<String, ExclusiveStepExecution> LIVE = new ConcurrentHashMap<>();
    /**
     * Every step that has asked and not yet given its ownership back, parked ones included: what a
     * controller stopping has to let go of. Emptied by {@link #abandonAll()}, since a JVM that outlives
     * Jenkins keeps its statics.
     */
    private static final Set<ExclusiveStepExecution> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private final String key;
    private final String executionId;
    private final String ownershipId;
    private final String designatedBy;
    private final String generation;
    private final String completedWhen;
    private final String enabledBy;
    private final String lease;
    private final String renewEvery;
    private final String renewalGrace;
    private final String guardGrace;
    private final String handoverWait;
    // Written down, and read before anything else on resume: a state this version does not know the
    // meaning of must not be acted on. Field initialisers do not run when a step is read back, so
    // state written before this field existed comes back as 0.
    private int stateVersion = STATE_VERSION;

    private transient volatile Admitted admitted;
    // The attempt still in flight, kept only so that a step which is over can call it off: a park is a
    // loop of rounds against three keys, and nothing else would stop it before handoverWait is out.
    private transient volatile CompletableFuture<Admission> asking;
    private BodyExecution body;
    // Written down, and that is the point of it: it is what the body's environment was expanded with,
    // so a resumed attempt can tell whether the ownership it just retook is still the one the body is
    // running under. Not transient, and not derivable from anything that survives a restart.
    private long admittedToken;
    // Written down, and for the same reason the token above is: a controller which goes down between
    // the body ending and the release it is waiting on coming back finds a step whose body is over,
    // whose context was never answered, and which has nothing left in memory to say so. Asking again
    // then takes a lease for work that is finished, and cancelling a body which has already ended does
    // nothing -- so the step would hang, on a lease nothing gives back. This is the record of what the
    // body did, and of which answer the context is still owed.
    private boolean bodyEnded;
    private Throwable bodyFailure;
    // Written down, so that a park which outlives the controller does not start handoverWait over.
    // Counted on the monotonic clock only: the time the controller was down is not in it.
    private long waitedNanos;
    // Guarded by this, like the phase: the timer which counts the wait, while there is one.
    private transient TimeSource.Cancellable waitTicker;
    private transient TimeSource waitTime;
    private transient long waitCountedAtNanos;
    // Guarded by this. Not written down, unlike bodyEnded: it is a question within one controller's
    // lifetime, and onResume() starts it over.
    private transient Phase phase = ASKING;

    /**
     * Where the step is within one controller's lifetime. Each revocation, refusal and body end is a
     * move between these, made under the monitor, so no two of them can both decide the outcome.
     */
    private sealed interface Phase {
    }

    /** Waiting for an admission. A resumed body may be replaying meanwhile. */
    private record Asking() implements Phase {
    }

    /** Ownership was lost before a body started: the admission coming back is refused. */
    private record Doomed(Throwable cause) implements Phase {
    }

    /** The context is answered with no body running: an admission coming back is given straight back. */
    private record Answered() implements Phase {
    }

    /** The body runs under the ownership. */
    private record Running() implements Phase {
    }

    /** Ownership was lost while the body ran: however the body ends, the step does not succeed. */
    private record RunningRevoked(Throwable cause) implements Phase {
    }

    /** The body's callback has run. */
    private record Ended() implements Phase {
    }

    /** The controller is stopping: the step is the next controller's to resume, and nothing is answered. */
    private record Abandoned() implements Phase {
    }

    private static final Phase ASKING = new Asking();
    private static final Phase ANSWERED = new Answered();
    private static final Phase RUNNING = new Running();
    private static final Phase ENDED = new Ended();
    private static final Phase ABANDONED = new Abandoned();

    ExclusiveStepExecution(final StepContext context, final PiplexExclusiveStep step) throws Exception {
        super(context);
        this.key = step.getKey();
        this.executionId = executionIdOf(context);
        this.ownershipId = context.get(Run.class).getExternalizableId() + '/' + executionId;
        this.designatedBy = step.getDesignatedBy();
        this.generation = step.getGeneration();
        this.completedWhen = step.getCompletedWhen();
        this.enabledBy = step.getEnabledBy();
        this.lease = step.getLease();
        this.renewEvery = step.getRenewEvery();
        this.renewalGrace = step.getRenewalGrace();
        this.guardGrace = step.getGuardGrace();
        this.handoverWait = step.getHandoverWait();
    }

    /**
     * Which of this build's asks this one is.
     *
     * <p>A build can be in two of these blocks at once -- two branches of a parallel stage competing
     * for the same resource, or one block nested in another -- and the lease is taken under an identity
     * that has to tell them apart, or both are let in on one lease and the first to finish gives it
     * away from under the other.
     *
     * <p>The flow node's id, which is the number Jenkins itself puts next to this block in the flow
     * graph, so the two logs and the lease agree on which ask is which -- and a random part after it. A
     * clone of this controller's home has the same owner id, the same build numbers and the same flow
     * graph, so without it a clone's build would be told it already holds this build's lease, and both
     * would run.
     *
     * <p>Written down with everything else here, so a resumed step asks under the same identity it held
     * before -- a fresh one would make a run coming back a rival of the lease it already holds.
     *
     * @param context the step's context
     * @return the id
     * @throws Exception if the context cannot be read
     */
    private static String executionIdOf(final StepContext context) throws Exception {
        final FlowNode node = context.get(FlowNode.class);
        final String unique = UUID.randomUUID().toString();
        // Never null on a CPS flow, which is the only place this step runs. A context without one is
        // some other flow engine, and the random part alone is a better answer than failing the build.
        return node == null ? unique : node.getId() + '/' + unique;
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
        synchronized (this) {
            phase = ASKING;
        }
        final AbortException unknown = unknownState(stateVersion, key);
        if (unknown != null) {
            // Through refuse, for the reason given below: the body may be replaying.
            refuse(unknown);
            return;
        }
        // Everything older is read by the checks below, and is this version's from here on.
        stateVersion = STATE_VERSION;
        if (bodyEnded) {
            answerForTheEndedBody();
            return;
        }
        if (ownershipId == null) {
            // Nothing wrote it down, which means a version of the plugin that had no such field did --
            // this build was inside the block when the plugin was upgraded. Asking again would be asking
            // under an identity that is half missing, and the name a body's context carries would be
            // null. Said in a sentence somebody can act on rather than left to fail as a null key.
            //
            // Through refuse rather than straight to the context: the body is replaying, and answering
            // the context while it runs leaves the guarded work under way with nothing holding the key
            // it was guarded by. Stopping it is the whole of what this sentence says has happened.
            refuse(new AbortException(
                    "piplex: this build was inside piplexExclusive when the plugin was upgraded, and "
                            + "what was written down of the step does not name the ownership it held. "
                            + "Nothing is holding '" + key + "' on its behalf -- run the build again."));
            return;
        }
        try {
            ask();
        } catch (final Exception failed) {
            // Through refuse, for the reason above: the body may be replaying, and it has to be stopped
            // rather than left running with nothing holding the key.
            refuse(PiplexInterruption.unavailableOnResume(key, failed));
        }
    }

    /**
     * @param written the state version a step was written down with
     * @param key     what the step guards
     * @return why that state cannot be resumed, or {@code null} when it can
     */
    static AbortException unknownState(final int written, final String key) {
        if (written >= 0 && written <= STATE_VERSION) {
            return null;
        }
        // Newer than this version: the plugin was downgraded under a running build. Guessing what the
        // fields mean is how a body that already ran gets run again.
        return new AbortException(
                "piplex: this build was inside piplexExclusive on '" + key + "' when the plugin was "
                        + "replaced by an older version, which cannot read the step's saved state (version "
                        + written + ", this plugin reads up to " + STATE_VERSION + "). Nothing is holding '"
                        + key + "' on its behalf -- run the build again.");
    }

    @Override
    public void stop(final Throwable cause) throws Exception {
        // Nothing here waits for the body to notice: cancelling is asynchronous, and the callback it
        // ends in is what gives the ownership back and completes the step.
        refuse(cause);
    }

    private void ask() throws Exception {
        final TaskListener listener = getContext().get(TaskListener.class);
        // Both from one reading of the settings, or a Save landing between two asks would give this
        // request the old owner id and a store built from the new form.
        final PiplexConfiguration configuration = PiplexConfiguration.require();
        final PiplexConfiguration.Configured configured = configuration.configuredFor(listener);
        final String duplicated = configuration.duplicatedOwner();
        if (duplicated != null) {
            listener.getLogger().println("piplex: WARNING another live controller uses the owner id '"
                    + duplicated + "'; designations naming it name both controllers");
        }
        // A body that is already there is one Jenkins is resuming after a restart. Everything about this
        // attempt is different because of it: the work is under way, so there is nothing to wait for and
        // nothing to start.
        final boolean resuming = body != null;
        final ExclusiveRequest request = request(configured.ownerId(), resuming);
        if (!resuming && !request.handoverWait().isZero()) {
            countWaiting(configured.time(), request.renewEvery());
        }
        IN_FLIGHT.add(this);
        final CompletableFuture<Admission> asked =
                configured.piplex().runs().begin(request).toCompletableFuture();
        asking = asked;
        asked.whenComplete((admission, error) -> {
            stopCountingWait();
            if (error instanceof CancellationException) {
                // This step called the attempt off, and it only does that once it has answered the
                // context. There is nothing left to refuse and nobody to tell.
                return;
            }
            if (error != null) {
                // The same answer as a store that could not even be asked, which onResume() gives.
                final Throwable cause = unwrapped(error);
                refuse(resuming ? PiplexInterruption.unavailableOnResume(key, cause) : cause);
            } else if (admission instanceof Admitted granted) {
                admit(granted);
            } else {
                refuse(resuming
                        ? PiplexInterruption.lostOnResume(key, admission)
                        : PiplexInterruption.of(key, admission));
            }
        });
    }

    private static Throwable unwrapped(final Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    private ExclusiveRequest request(final String ownerId, final boolean resuming)
            throws Exception {
        final Run<?, ?> run = getContext().get(Run.class);
        final ExclusiveRequest.Builder builder = ExclusiveRequest.builder(key)
                .ownedBy(ownerId)
                // The build's externalizable id -- "eod#142", the same one the ownership above is
                // named by -- which is what a reader of two logs needs to tell them apart, and what
                // makes a second run on this controller lose the lease rather than share it. Not the
                // display name: a pipeline may set its own, and two builds which set the same one
                // would be one holder as far as the lease is concerned, and both let in.
                .runId(run.getExternalizableId())
                // And which block of that build, so two of them at once do not share one lease either.
                .executionId(executionId)
                .designatedBy(designatedBy)
                .completedWhen(completedWhen)
                .enabledBy(enabledBy)
                .lease(Durations.parse(lease, ExclusiveRequest.DEFAULT_LEASE, "lease"))
                // Parking is for a candidate with nothing running. A run whose body is resuming cannot
                // park: it would be doing the work for as long as it waited for permission to do it.
                .handoverWait(resuming
                        ? Duration.ZERO
                        : notWaitedYet(Durations.parse(handoverWait, Duration.ZERO, "handoverWait")));
        if (generation != null && !generation.isBlank()) {
            builder.generation(Generation.of(generation.trim()));
        }
        if (renewEvery != null && !renewEvery.isBlank()) {
            builder.renewEvery(Durations.parse(renewEvery, null, "renewEvery"));
        }
        if (renewalGrace != null && !renewalGrace.isBlank()) {
            builder.renewalGrace(Durations.parse(renewalGrace, null, "renewalGrace"));
        }
        if (guardGrace != null && !guardGrace.isBlank()) {
            builder.guardGrace(Durations.parse(guardGrace, null, "guardGrace"));
        }
        return builder.build();
    }

    /**
     * @return how much waiting is written down so far, for a test to wait on
     */
    synchronized Duration waited() {
        return Duration.ofNanos(waitedNanos);
    }

    /**
     * @param configured the whole handoverWait
     * @return what is left of it after the waiting done before a restart
     */
    private synchronized Duration notWaitedYet(final Duration configured) {
        final Duration left = configured.minusNanos(waitedNanos);
        return left.isNegative() ? Duration.ZERO : left;
    }

    /**
     * Keeps {@code waitedNanos} written down while the step parks, one tick per {@code every}.
     *
     * @param time  where time comes from
     * @param every how often to write it down
     */
    private void countWaiting(final TimeSource time, final Duration every) {
        synchronized (this) {
            waitTime = time;
            waitCountedAtNanos = time.nanos();
            waitTicker = time.schedule(every, () -> tick(every));
        }
    }

    private void tick(final Duration every) {
        synchronized (this) {
            if (waitTicker == null) {
                return;
            }
            countWaited();
            waitTicker = waitTime.schedule(every, () -> tick(every));
        }
        getContext().saveState();
    }

    private void stopCountingWait() {
        synchronized (this) {
            if (waitTicker == null) {
                return;
            }
            waitTicker.cancel();
            waitTicker = null;
            countWaited();
        }
    }

    // Only under the monitor.
    private void countWaited() {
        final long now = waitTime.nanos();
        waitedNanos += now - waitCountedAtNanos;
        waitCountedAtNanos = now;
    }

    private void admit(final Admitted granted) {
        admitted = granted;
        // Registered before the body starts, so a designation that changes in the same second as the
        // admission still stops it. The listener runs on a piplex thread, and all it does is ask Jenkins
        // to cancel -- the stopping itself happens where Jenkins does it.
        // Ownership can be gone before this line: the watches are armed before the admission is handed
        // over, so the listener may fire here, with no body to cancel. Remembered, not dropped.
        granted.onRevoked(revocation -> cancel(PiplexInterruption.revoked(key, revocation)));
        final boolean abandoned;
        synchronized (this) {
            abandoned = phase == ABANDONED;
        }
        if (abandoned) {
            // Read after the handle was published, so either this or abandon() sees the other.
            granted.abandon();
            return;
        }
        final boolean tooLate;
        final Throwable fencedOut;
        Throwable doNotRun;
        synchronized (this) {
            if (body != null) {
                // Resuming after a restart. The body is Jenkins' to bring back -- its program state was
                // written down and is replayed from where it stopped -- and starting another one here
                // would run the guarded work twice on the same controller. All this attempt owed the
                // resumed body was the ownership it is running under, and that is now retaken.
                //
                // Retaken under the same lease or under a new one, and the difference matters. A lease
                // that stood through the restart comes back with the token it had. One that lapsed and
                // was taken and given back comes back higher -- and the body has the old number in its
                // environment, already passed to whatever it protects. There is no way to correct that
                // from here: the body is mid-replay and its environment was expanded when it started.
                fencedOut = granted.fencingToken() == admittedToken
                        ? null
                        : PiplexInterruption.fencedOut(key, admittedToken, granted.fencingToken());
                if (fencedOut != null) {
                    lost(fencedOut);
                }
            } else {
                fencedOut = null;
            }
            tooLate = phase == ANSWERED || phase == ENDED;
            doNotRun = fencedOut == null ? revocationOf(phase) : null;
            if (!tooLate && fencedOut == null && doNotRun == null) {
                phase = RUNNING;
                // Answered for by name from here on, and not one moment earlier. Registered before the
                // token was compared, a resumed step would hand piplexToken() the lease it has just
                // retaken -- in the moment before it stops the body for running under a different one,
                // and to a step which is about to be stopped for using exactly that number.
                //
                // Still inside the monitor and still ahead of the body, so the first thing the body
                // does can be to ask.
                LIVE.put(ownershipId, this);
            }
            if (body == null && !tooLate && doNotRun == null) {
                // The step can be over before the answer arrives: a build cancelled while the attempt
                // was in flight is refused when there is no body to cancel, and the context is answered
                // there.
                admittedToken = granted.fencingToken();
                // Under the monitor, so a revocation racing the start waits for the body to exist.
                try {
                    body = getContext().newBodyInvoker()
                            .withCallback(new ReleaseOnFinish(this))
                            .withContexts(environment(granted), new PiplexOwnership(key, ownershipId))
                            .start();
                } catch (final Exception couldNotStart) {
                    doNotRun = couldNotStart;
                }
            }
        }
        if (tooLate) {
            // Nothing to answer and nothing to stop, so the ownership is all there is left to give
            // back. Started anyway, it would be a body under a context that has been answered, and
            // whether the lease comes back then is Jenkins' to decide rather than this step's.
            //
            // Before the token, and that order is the whole of it: a resumed body can finish while
            // this attempt is still in flight, and then there is no body left to stop over a token it
            // has already used. What is left is a lease nothing holds, which would otherwise be
            // renewed until the controller stops.
            release();
        } else if (fencedOut != null) {
            refuse(fencedOut);
        } else if (doNotRun != null) {
            refuse(doNotRun);
        }
    }

    /**
     * The body's environment, with the fencing token in it.
     *
     * <p>A variable rather than only a step, because most guarded work is a shell command and reading
     * an environment variable is what a shell command can do. It is expanded once, here, so a body
     * which outlives a controller restart carries the token of the lease it started under -- which is
     * why a resume that comes back with a different one stops the body instead of correcting it.
     *
     * @param granted the ownership this body runs under
     * @return the expander to hand the body, with whatever was already there kept
     * @throws Exception if the context cannot be read
     */
    private EnvironmentExpander environment(final Admitted granted) throws Exception {
        return EnvironmentExpander.merge(
                getContext().get(EnvironmentExpander.class),
                EnvironmentExpander.constant(
                        Map.of("PIPLEX_FENCING_TOKEN", Long.toString(granted.fencingToken()))));
    }

    /**
     * @param id the name a body's context carries
     * @return the ownership in force under that name, or {@code null} when there is none
     */
    static Admitted ownershipOf(final String id) {
        final ExclusiveStepExecution execution = LIVE.get(id);
        return execution == null ? null : execution.admitted;
    }

    /**
     * @param phase where the step is
     * @return why ownership was lost, or {@code null} when it was not
     */
    private static Throwable revocationOf(final Phase phase) {
        if (phase instanceof Doomed doomed) {
            return doomed.cause();
        }
        if (phase instanceof RunningRevoked revoked) {
            return revoked.cause();
        }
        return null;
    }

    /**
     * Stops the body, or remembers that it must not be started.
     *
     * <p>Remembered in both cases. Cancelling is asynchronous, so a body can still end well after
     * this, and that must not make the step a success.
     *
     * @param cause why it may not run
     */
    private void cancel(final Throwable cause) {
        final BodyExecution running;
        synchronized (this) {
            running = body;
            lost(cause);
            if (running == null) {
                return;
            }
        }
        // Outside the monitor: cancelling ends in a callback which comes back through this object.
        running.cancel(cause);
    }

    /**
     * Records that ownership is gone, unless the step is already over. Only under the monitor.
     *
     * @param cause why it may not run
     */
    private void lost(final Throwable cause) {
        if (phase == ASKING || phase == RUNNING) {
            // A resumed body replays while Asking, so a body is what tells the two losses apart.
            phase = body == null ? new Doomed(cause) : new RunningRevoked(cause);
        }
    }

    /**
     * Ends the step, and the body with it when there is one.
     *
     * @param cause why it may not run
     */
    private void refuse(final Throwable cause) {
        final BodyExecution running;
        synchronized (this) {
            if (phase == ABANDONED) {
                return;
            }
            running = body;
            if (running != null) {
                // The body may still end well before the cancel below lands.
                lost(cause);
            } else {
                // Two refusals can race -- a stop against an attempt coming back -- and only the
                // first may answer.
                if (phase == ANSWERED || phase == ENDED) {
                    return;
                }
                // Remembered for an attempt still in flight: the context is answered below, so what
                // that attempt comes back with must be given back rather than started.
                phase = ANSWERED;
            }
        }
        if (running != null) {
            // Cancelling is what ends the step here: the body's callback releases the ownership and
            // completes the context, and doing either from here would be a second answer to the same
            // question.
            running.cancel(cause);
            return;
        }
        // An attempt still parking is called off rather than left to run out handoverWait against
        // three keys nobody is waiting on. Cancelled after the flag above, so anything it comes back
        // with is given straight back.
        final CompletableFuture<Admission> inFlight = asking;
        if (inFlight != null) {
            inFlight.cancel(false);
        }
        release().whenComplete((ignored, never) -> getContext().onFailure(cause));
    }

    /**
     * The body is over: nothing may be started under this step again, and the ownership goes back.
     *
     * <p>The phase matters as much as the release. A resumed step asks again while Jenkins replays the
     * body, and the body can finish first -- leaving an admission on its way to a step whose body is
     * not running, whose context is answered, and which has no other way to know it. Without
     * {@code Ended}, that admission is held and renewed until the controller stops. And a body that
     * ended well after ownership was lost ends the step with the revocation, not a success.
     *
     * <p>{@code bodyEnded} is the same problem across the restart rather than within it, and it is
     * why what the body did is written down here rather than only passed on. A controller that goes
     * down inside the release below comes back to a step whose body is over and whose context was
     * never answered, and nothing in memory is left to say either.
     *
     * @param cause what the body ended with, or {@code null} when it ended well
     * @return completes, once the lease has been given back, with what the step ends with:
     *         {@code null} for a success
     */
    private CompletionStage<Throwable> bodyFinished(final Throwable cause) {
        final Throwable outcome;
        synchronized (this) {
            // The body's own failure first: it is what the cancel, when it lands, ends it with anyway.
            outcome = cause != null ? cause : revocationOf(phase);
            phase = ENDED;
            bodyEnded = true;
            bodyFailure = writable(outcome);
        }
        // Written down before the release rather than after it, because the release is the whole of
        // the window this covers: a controller which goes down inside it comes back to a step that has
        // to answer for a body it can no longer see. Not waited for -- this runs on the CPS VM thread,
        // the one thread nothing here is allowed to hold.
        getContext().saveState();
        return release().thenApply(ignored -> outcome);
    }

    /**
     * Answers for a body which was over before the controller went down.
     *
     * <p>Nothing is asked for again. There is no work left to guard, and a step which asked would be
     * admitted for a night that is finished -- then told to stop a body which has already stopped, and
     * left holding a lease with nobody to give it back. The lease it held before is not this step's to
     * end either: the handle went with the controller, so what is left is to let it lapse.
     *
     * <p>The body's own value does not survive, which is why a block never returns it -- here or in
     * {@link ReleaseOnFinish}. Losing the failure instead would cost a build its result.
     */
    private void answerForTheEndedBody() {
        synchronized (this) {
            phase = ANSWERED;
        }
        final Throwable failed = bodyFailure;
        release().whenComplete((ignored, never) -> {
            if (failed != null) {
                getContext().onFailure(failed);
            } else {
                getContext().onSuccess(null);
            }
        });
    }

    /**
     * The cause, in a form the program it is written into can hold.
     *
     * <p>{@link FlowInterruptedException} carries the result the build ends as, and {@link AbortException}
     * is a sentence; both are this plugin's own and both are written with builds all day. Anything else
     * came out of the body's work and may carry any state at all -- and one field of it that cannot be
     * written would make the whole program unsaveable, which is this record's own failure arrived at
     * from the other side. Kept as its text instead: a body that failed fails the step either way.
     *
     * @param cause what the body ended with, or {@code null} when it ended well
     * @return what to write down
     */
    private static Throwable writable(final Throwable cause) {
        if (cause == null
                || cause instanceof FlowInterruptedException
                || cause instanceof AbortException) {
            return cause;
        }
        return new AbortException(cause.toString());
    }

    /**
     * Gives the ownership back.
     *
     * @return completes when the lease is gone, or when waiting for it has gone on long enough that
     *         letting it lapse is the better answer. Never completes exceptionally: an unreleased
     *         lease lapses, and there is nothing for a caller to do about it
     */
    private CompletionStage<Void> release() {
        final Admitted granted = admitted;
        admitted = null;
        // Before the early return as well: a step which never held anything may still have been put
        // there by an attempt whose admission was given straight back.
        //
        // Never under a name that is missing: a step written down by a version of the plugin that had
        // no ownership id is refused through here, and there is nothing it can have been registered
        // under. Asking the map about a null name would throw past the refusal instead.
        if (ownershipId != null) {
            LIVE.remove(ownershipId, this);
        }
        IN_FLIGHT.remove(this);
        if (granted == null) {
            return CompletableFuture.completedFuture(null);
        }
        // Waited for rather than fired and forgotten: until the lease is gone, the controller taking
        // over has to wait for it to lapse, and that wait is the handover. Bounded, because a store
        // that has stopped answering must not also stop the build from finishing -- the lease lapses
        // on its own soon enough.
        return granted.release()
                .toCompletableFuture()
                .orTimeout(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .handle((ignored, failed) -> (Void) null);
    }

    /**
     * Lets go of every step on this controller without answering any of them, for a controller that
     * is stopping while the JVM is not.
     *
     * <p>Nothing is given back and nobody is told. The builds are suspended and resume on the next
     * start, and a lease left to lapse is retaken there with the fencing token the body already has;
     * given back, it would come back with a new one and stop the body. What stops is the renewing, so
     * a controller that does not come back does not keep its leases for ever.
     */
    static void abandonAll() {
        for (final ExclusiveStepExecution execution : IN_FLIGHT) {
            execution.abandon();
        }
    }

    private void abandon() {
        synchronized (this) {
            phase = ABANDONED;
            if (waitTicker != null) {
                waitTicker.cancel();
                waitTicker = null;
            }
        }
        // Cancelled after the phase is set, so neither what it comes back with nor its failure is acted on.
        final CompletableFuture<Admission> inFlight = asking;
        if (inFlight != null) {
            inFlight.cancel(false);
        }
        final Admitted granted = admitted;
        admitted = null;
        if (ownershipId != null) {
            LIVE.remove(ownershipId, this);
        }
        IN_FLIGHT.remove(this);
        if (granted != null) {
            granted.abandon();
        }
    }

    /**
     * Gives the ownership back when the body is done, whichever way it went.
     *
     * <p>Not a {@link BodyExecutionCallback.TailCall}, which is the obvious way to write this and the
     * wrong one: its {@code finished} runs on the CPS VM thread -- the single thread every pipeline on
     * this controller shares -- and giving a lease back is a round trip to a store that may be taking
     * its time. The context is completed from the release's own completion instead, so the order the
     * handover depends on is kept, the lease gone before the step ends, with nothing waiting on that
     * thread.
     */
    private static final class ReleaseOnFinish extends BodyExecutionCallback {

        private static final long serialVersionUID = 1L;

        private final ExclusiveStepExecution execution;

        ReleaseOnFinish(final ExclusiveStepExecution execution) {
            this.execution = execution;
        }

        @Override
        public void onSuccess(final StepContext context, final Object result) {
            // Never the body's value: after a restart it is gone, and a block whose value depends on
            // whether the controller restarted is worse than one which has none.
            execution.bodyFinished(null).whenComplete((outcome, never) -> answer(context, outcome));
        }

        @Override
        public void onFailure(final StepContext context, final Throwable cause) {
            execution.bodyFinished(cause).whenComplete((outcome, never) -> answer(context, outcome));
        }

        private static void answer(final StepContext context, final Throwable outcome) {
            if (outcome == null) {
                context.onSuccess(null);
            } else {
                context.onFailure(outcome);
            }
        }
    }
}
