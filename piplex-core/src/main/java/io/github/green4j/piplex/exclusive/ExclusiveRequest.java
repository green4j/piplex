/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.exclusive;

import io.github.green4j.piplex.Environment;
import io.github.green4j.piplex.Generation;
import io.github.green4j.piplex.switches.Switches;

import java.time.Duration;
import java.util.Objects;

/**
 * What a run is asking for.
 *
 * @param key          what is being competed for -- a resource, not a pipeline name, so that two
 *                     different pipelines touching the same thing can name the same key
 * @param ownerId      who is asking; the <b>deployment's</b> identity, not a worker's or a build's,
 *                     because it is what a designation names and what recovers an unknown acquisition
 * @param runId        which run is asking, for the log and for the records written
 * @param executionId  which attempt within that run is asking, or {@code null} when a run makes only
 *                     one. It goes into the identity the lease is taken under and nowhere else -- not
 *                     into the log, not into the records -- because what it is for is telling two
 *                     concurrent asks by <b>one run</b> apart, which is a thing only the lease cares
 *                     about. It must be the same string again after a restart, or a run coming back
 *                     would not recognise the lease it already holds
 * @param designatedBy the key holding the designated owner, or {@code null} to elect instead
 * @param generation   which round of work this is, or {@code null}
 * @param completedWhen the milestone whose reaching this generation means there is nothing to do,
 *                     or {@code null}. It is read twice -- once before the lease and once with it in
 *                     hand -- and both reads rest on the caller publishing the milestone <b>while the
 *                     admission is still held</b>. Nothing in the store enforces that: a milestone is
 *                     an ordinary key, and a run which gives the admission back and publishes
 *                     afterwards leaves a gap in which the next candidate is admitted to redo the
 *                     generation, which is the one thing this field is there to prevent
 * @param enabledBy    the key holding the on/off switch, or {@code null} to not consult one
 * @param activeKey    an external store key, taken as is, whose value must equal {@code activeValue}
 *                     for the run to proceed; {@code null} to not consult one. Piplex only reads it.
 *                     Not combined with {@code designatedBy}: both name who runs
 * @param activeValue  the value {@code activeKey} must hold; set together with it
 * @param lease        how long the lease lasts unless renewed; it bounds how long a handover takes in
 *                     the bad case, so it is short, and has nothing to do with how long the work runs
 * @param renewEvery   how often to renew
 * @param renewalGrace how long an unreachable store is tolerated before ownership is given up; positive.
 *                     It can only bring that moment forward, never push it back: ownership ends when
 *                     the lease's own term does, whether or not anybody could be asked about it
 * @param guardGrace   how long a designation or switch which cannot be read is tolerated before
 *                     ownership is given up; positive. Separate from {@code renewalGrace} because
 *                     the two fail separately: a lease renewed happily while one key cannot be read
 *                     is a run nothing is left able to stop
 * @param handoverWait how long to wait, rather than give up, when somebody else is designated
 */
public record ExclusiveRequest(String key,
                               String ownerId,
                               String runId,
                               String executionId,
                               String designatedBy,
                               Generation generation,
                               String completedWhen,
                               String enabledBy,
                               String activeKey,
                               String activeValue,
                               Duration lease,
                               Duration renewEvery,
                               Duration renewalGrace,
                               Duration guardGrace,
                               Duration handoverWait) {

    /** The default lease: short, because it is the handover bound and not the work's duration. */
    public static final Duration DEFAULT_LEASE = Duration.ofSeconds(60);


    /**
     * Validates the request.
     */
    public ExclusiveRequest {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(renewEvery, "renewEvery");
        Objects.requireNonNull(renewalGrace, "renewalGrace");
        Objects.requireNonNull(guardGrace, "guardGrace");
        Objects.requireNonNull(handoverWait, "handoverWait");
        // A blank one does not fail either: every key this request turns on is a prefix plus this, so
        // what it competes for is the prefix itself -- one lease shared by every piece of work in the
        // environment, which reads as contention nobody can account for.
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        if (renewEvery.isNegative() || renewEvery.isZero() || renewEvery.compareTo(lease) >= 0) {
            throw new IllegalArgumentException("renewEvery must be positive and shorter than the lease");
        }
        // Zero does not fail on its own either: it puts the deadline at the admission itself, so the
        // run is admitted and revoked in the same breath, which reads as a coordination fault.
        if (renewalGrace.isNegative() || renewalGrace.isZero()) {
            throw new IllegalArgumentException("renewalGrace must be positive");
        }
        if (guardGrace.isNegative() || guardGrace.isZero()) {
            throw new IllegalArgumentException("guardGrace must be positive");
        }
        if (handoverWait.isNegative()) {
            throw new IllegalArgumentException("handoverWait must not be negative");
        }
        // Or the work switch would be one deployment's switch of some other work.
        if (enabledBy != null && Switches.namesAnOwner(enabledBy)) {
            throw new IllegalArgumentException(
                    "The enabledBy switch must not have a segment starting with '@', but got '" + enabledBy + "'");
        }
        if ((activeKey == null) != (activeValue == null)) {
            throw new IllegalArgumentException("activeKey and activeValue must be set together");
        }
        if (activeKey != null) {
            // Two answers to who runs: when they disagree, nobody does.
            if (designatedBy != null) {
                throw new IllegalArgumentException("designatedBy and activeKey must not be combined");
            }
            if (activeKey.isBlank()) {
                throw new IllegalArgumentException("activeKey must not be blank");
            }
            // Every key piplex writes is under this, in every environment, so an active key there
            // would collide with one of them -- if not today's, then one of another environment's.
            if (activeKey.startsWith(Environment.ROOT)) {
                throw new IllegalArgumentException(
                        "activeKey must not be under '" + Environment.ROOT + "', but got '"
                                + activeKey + "'");
            }
            if (activeValue.isBlank()) {
                throw new IllegalArgumentException("activeValue must not be blank");
            }
        }
        if (completedWhen != null && generation == null) {
            throw new IllegalArgumentException("completedWhen needs a generation to compare against");
        }
    }

    /**
     * @param key what is being competed for
     * @return a builder with the defaults filled in
     */
    public static Builder builder(final String key) {
        return new Builder(key);
    }

    /**
     * Assembles a request. Only the key, the owner and the run have to be given.
     */
    public static final class Builder {

        private final String key;
        private String ownerId;
        private String runId;
        private String executionId;
        private String designatedBy;
        private Generation generation;
        private String completedWhen;
        private String enabledBy;
        private String activeKey;
        private String activeValue;
        private Duration lease = DEFAULT_LEASE;
        private Duration renewEvery;
        private Duration renewalGrace;
        private Duration guardGrace;
        private Duration handoverWait = Duration.ZERO;

        private Builder(final String key) {
            this.key = key;
        }

        /**
         * @param value the deployment's identity
         * @return this
         */
        public Builder ownedBy(final String value) {
            this.ownerId = value;
            return this;
        }

        /**
         * @param value which run is asking
         * @return this
         */
        public Builder runId(final String value) {
            this.runId = value;
            return this;
        }

        /**
         * @param value which attempt within that run is asking, where a run can make more than one at
         *              once; it must survive a restart
         * @return this
         */
        public Builder executionId(final String value) {
            this.executionId = value;
            return this;
        }

        /**
         * @param value the key holding the designated owner
         * @return this
         */
        public Builder designatedBy(final String value) {
            this.designatedBy = value;
            return this;
        }

        /**
         * @param value which round of work this is
         * @return this
         */
        public Builder generation(final Generation value) {
            this.generation = value;
            return this;
        }

        /**
         * @param value the milestone which means the work is already done
         * @return this
         */
        public Builder completedWhen(final String value) {
            this.completedWhen = value;
            return this;
        }

        /**
         * @param value the key holding the on/off switch
         * @return this
         */
        public Builder enabledBy(final String value) {
            this.enabledBy = value;
            return this;
        }

        /**
         * @param key   the external key to consult, taken as is
         * @param value what it must hold for the run to proceed
         * @return this
         */
        public Builder activeWhen(final String key, final String value) {
            this.activeKey = key;
            this.activeValue = value;
            return this;
        }

        /**
         * @param value how long the lease lasts unless renewed
         * @return this
         */
        public Builder lease(final Duration value) {
            this.lease = value;
            return this;
        }

        /**
         * @param value how often to renew; a third of the lease by default
         * @return this
         */
        public Builder renewEvery(final Duration value) {
            this.renewEvery = value;
            return this;
        }

        /**
         * @param value how long an unreachable store is tolerated; one lease by default. Longer than
         *              the lease has no effect beyond it -- the term of the lease is the outer bound
         * @return this
         */
        public Builder renewalGrace(final Duration value) {
            this.renewalGrace = value;
            return this;
        }

        /**
         * @param value how long a designation or switch which cannot be read at all is tolerated; the
         *              same as {@code renewalGrace} by default
         * @return this
         */
        public Builder guardGrace(final Duration value) {
            this.guardGrace = value;
            return this;
        }

        /**
         * @param value how long to wait rather than give up when somebody else is designated
         * @return this
         */
        public Builder handoverWait(final Duration value) {
            this.handoverWait = value;
            return this;
        }

        /**
         * @return the request
         */
        public ExclusiveRequest build() {
            // Renewal and grace follow the lease unless asked for, so that shortening the lease for a
            // faster handover does not silently leave renewals too far apart to keep it.
            final Duration every = renewEvery != null ? renewEvery : lease.dividedBy(3);
            final Duration grace = renewalGrace != null ? renewalGrace : lease;
            // And a key which cannot be read is tolerated for as long as a store which cannot be
            // reached, unless somebody says otherwise: both are the same question -- how long a run may
            // go on with nothing being learnt about what permits it.
            final Duration guard = guardGrace != null ? guardGrace : grace;
            return new ExclusiveRequest(key, ownerId, runId, executionId, designatedBy, generation,
                    completedWhen, enabledBy, activeKey, activeValue, lease, every, grace, guard, handoverWait);
        }
    }
}
