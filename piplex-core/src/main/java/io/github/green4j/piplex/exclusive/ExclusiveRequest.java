/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.exclusive;

import io.github.green4j.piplex.Generation;

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
 * @param designatedBy the key holding the designated owner, or {@code null} to elect instead
 * @param generation   which round of work this is, or {@code null}
 * @param completedWhen the milestone whose reaching this generation means there is nothing to do,
 *                     or {@code null}
 * @param enabledBy    the key holding the on/off switch, or {@code null} to not consult one
 * @param lease        how long the lease lasts unless renewed; it bounds how long a handover takes in
 *                     the bad case, so it is short, and has nothing to do with how long the work runs
 * @param renewEvery   how often to renew
 * @param renewalGrace how long an unreachable store is tolerated before ownership is given up
 * @param handoverWait how long to wait, rather than give up, when somebody else is designated
 */
public record ExclusiveRequest(String key,
                               String ownerId,
                               String runId,
                               String designatedBy,
                               Generation generation,
                               String completedWhen,
                               String enabledBy,
                               Duration lease,
                               Duration renewEvery,
                               Duration renewalGrace,
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
        Objects.requireNonNull(handoverWait, "handoverWait");
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        if (renewEvery.isNegative() || renewEvery.isZero() || renewEvery.compareTo(lease) >= 0) {
            throw new IllegalArgumentException("renewEvery must be positive and shorter than the lease");
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
        private String designatedBy;
        private Generation generation;
        private String completedWhen;
        private String enabledBy;
        private Duration lease = DEFAULT_LEASE;
        private Duration renewEvery;
        private Duration renewalGrace;
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
         * @param value how long an unreachable store is tolerated; one lease by default
         * @return this
         */
        public Builder renewalGrace(final Duration value) {
            this.renewalGrace = value;
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
            return new ExclusiveRequest(key, ownerId, runId, designatedBy, generation, completedWhen,
                    enabledBy, lease, every, grace, handoverWait);
        }
    }
}
