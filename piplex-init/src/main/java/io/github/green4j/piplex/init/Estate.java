/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.init;

import io.github.green4j.piplex.Environment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Everything a deployment has to decide, answered once.
 *
 * <p>The four places these facts are written by hand today -- each work's {@code piplexExclusive}
 * block, each controller's configuration, the cluster's ACL and the operator jobs -- are generated
 * from this instead, so they agree by construction. What they cannot do is agree with reality: that
 * is what {@link #problems()} is for, and why it is read before anything is written.
 *
 * @param environment  which set of orchestrations this is
 * @param controllers  the Jenkins controllers competing for the work
 * @param nodes        the discas cluster, as {@code nodeId=host:port} entries
 * @param transport    how a controller reaches the cluster
 * @param secretsDir   the directory each controller reads its key material from
 * @param operator     whose identity operator writes are made under
 * @param work         every piece of work being guarded, and the keys each one names
 * @param grants       how narrowly the ACL is written
 */
public record Estate(Environment environment,
                     List<Controller> controllers,
                     String nodes,
                     Transport transport,
                     String secretsDir,
                     Operator operator,
                     List<Work> work,
                     Grants grants) {

    /**
     * Where key material lives on a controller, which is all a configuration may say about it.
     *
     * <p>Not a default anybody has to accept: it is one field, and what goes in it is a path. What
     * must not be a field is the material itself -- a generated configuration is committed
     * somewhere, and a pass phrase in it is a pass phrase published.
     */
    public static final String SECRETS = "/run/secrets";

    /** How a controller reaches the cluster, which decides what a client id means there. */
    public enum Transport {
        /** A trusted network: a client id is a claim, and a second identity separates nothing. */
        ALLOWALL,
        /** A shared token over TLS. */
        TOKEN,
        /** A client certificate whose CN is the client id. */
        MTLS
    }

    /** How narrowly each identity is granted, which is the other half of the ACL. */
    public enum Grants {
        /** One line per identity, covering the environment. No upkeep, no separation within it. */
        PER_ENVIRONMENT,
        /** Every key named. Stops a controller writing work it does not run, and costs an edit. */
        PER_KEY
    }

    /**
     * @param ownerId  what designations name, and what this controller calls itself
     * @param clientId what the cluster authenticates and the ACL grants
     */
    public record Controller(String ownerId, String clientId) {
    }

    /**
     * Whose identity operator writes are made under.
     *
     * @param clientId      the operations identity, or {@code null} to act as the controller
     * @param credentialsId the Jenkins credential carrying it, or {@code null}
     */
    public record Operator(String clientId, String credentialsId) {

        /** Acting as the controller, which is what a shared ACL intends. */
        public static final Operator SHARED = new Operator(null, null);

        /** @return whether operator writes are made under an identity of their own */
        public boolean apart() {
            return named(clientId);
        }
    }

    /**
     * One piece of work and the keys it names, which is what its guarded pipeline's block says.
     *
     * <p>The four guards travel together. A handover reads all of them to say whether the work can
     * now run where it was just sent, so naming one belonging to another piece of work reports on
     * something nobody asked about. That is why the operator jobs offer a choice of work rather than
     * four key fields to fill in.
     *
     * @param key           the resource being protected
     * @param enabledBy     the switch, or {@code null} where the work names none
     * @param designatedBy  the designation key, or {@code null} where an active key drives it
     * @param activeKey     the key an external system writes, or {@code null}
     * @param completedWhen the milestone published, or {@code null} where nothing is published
     * @param lease         the ownership term, or {@code null} to take the step's own
     * @param handoverWait  how long a candidate parks, or {@code null} to take the step's own
     */
    public record Work(String key, String enabledBy, String designatedBy,
                       String activeKey, String completedWhen,
                       String lease, String handoverWait) {

        /**
         * The guards alone, with the two timings left to the step's own defaults.
         *
         * @param key           the resource being protected
         * @param enabledBy     the switch, or {@code null} where the work names none
         * @param designatedBy  the designation key, or {@code null} where an active key drives it
         * @param activeKey     the key an external system writes, or {@code null}
         * @param completedWhen the milestone published, or {@code null}
         * @return the work
         */
        public static Work of(final String key, final String enabledBy, final String designatedBy,
                              final String activeKey, final String completedWhen) {
            return new Work(key, enabledBy, designatedBy, activeKey, completedWhen, null, null);
        }

        /** @return whether piplex itself holds who runs, rather than following somebody else's key */
        public boolean designated() {
            return named(designatedBy);
        }

        /** @return whether the work consults a switch, and so can be stopped or drained */
        public boolean switched() {
            return named(enabledBy);
        }

        /** @return whether the work publishes a milestone */
        public boolean publishes() {
            return named(completedWhen);
        }

        /** @return whether somebody else's key decides who runs */
        public boolean follows() {
            return named(activeKey);
        }
    }

    /**
     * @return the trust anchors every controller verifies the nodes with
     */
    public String truststore() {
        return secretsDir + "/discas-trust.p12";
    }

    /**
     * @param controller whose certificate it is
     * @return the client certificate that controller presents, whose CN is its client id
     */
    public String keystore(final Controller controller) {
        return secretsDir + '/' + controller.clientId() + ".p12";
    }

    /**
     * The switches the estate knows, which is what a drain has to cover.
     *
     * <p>A drain is a question about the machine rather than about one piece of work: it is asked
     * because the controller is being patched, and everything running there has to stop. So it
     * writes a per-owner disable on every one of these, not on a chosen one.
     *
     * @return each switch key once, in the order the work was named
     */
    public List<String> switchKeys() {
        final Set<String> keys = new LinkedHashSet<>();
        for (final Work one : work) {
            if (one.switched()) {
                keys.add(one.enabledBy());
            }
        }
        return List.copyOf(keys);
    }

    /**
     * What is wrong with this estate, in the words somebody can act on.
     *
     * <p>Collected rather than thrown: a dialogue shows all of them at once, and the first answer
     * somebody gave is rarely the only one they got wrong.
     *
     * @return every problem found, empty when there are none
     */
    public List<String> problems() {
        final List<String> found = new ArrayList<>();
        if (controllers.isEmpty()) {
            found.add("No controllers are named, and the work has to run somewhere");
        }
        names(found);
        prefixes(found);
        work(found);
        identity(found);
        if (transport != Transport.ALLOWALL && !named(secretsDir)) {
            found.add("This cluster is reached over " + transport.name().toLowerCase(Locale.ROOT)
                    + ", and no directory is named for the key material it needs. Name one -- the "
                    + "files go there, never into a configuration");
        }
        if (nodes == null || nodes.isBlank()) {
            found.add("No discas nodes are named, so no controller can reach the store");
        }
        return found;
    }

    private void names(final List<String> found) {
        final Set<String> owners = new LinkedHashSet<>();
        final Set<String> clients = new LinkedHashSet<>();
        for (final Controller controller : controllers) {
            if (controller.ownerId() == null || controller.ownerId().isBlank()) {
                found.add("A controller has no owner id, and a designation cannot name it");
            } else if (!fileSegment(controller.ownerId())) {
                found.add("Controller '" + controller.ownerId() + "' is not one file name. Owner ids "
                        + "become directories under the generated controllers directory, so they "
                        + "must not be absolute, '.', '..', or contain '/' or '\\'");
            } else if (!owners.add(controller.ownerId())) {
                found.add("Two controllers call themselves '" + controller.ownerId()
                        + "'. A designation naming it names two machines, and which one runs is chance");
            }
            if (controller.clientId() != null && !controller.clientId().isBlank()
                    && !clients.add(controller.clientId())) {
                found.add("Two controllers connect as '" + controller.clientId()
                        + "', so the ACL cannot tell them apart");
            }
        }
    }

    private void prefixes(final List<String> found) {
        if (grants != Grants.PER_KEY && !drainsItself()) {
            return;
        }
        // A grant matches raw bytes, not path segments, so a per-owner drain key under '.../@euc1-blue'
        // is also matched by '.../@euc1-blue-2'. Nothing in discas checks this, and the ACL file that
        // ships says so; here is where it can be checked.
        for (final Controller one : controllers) {
            for (final Controller other : controllers) {
                if (one != other && other.ownerId() != null && one.ownerId() != null
                        && other.ownerId().startsWith(one.ownerId())
                        && !other.ownerId().equals(one.ownerId())) {
                    found.add("'" + one.ownerId() + "' is a prefix of '" + other.ownerId()
                            + "'. Grants match raw text rather than path segments, so a grant for the "
                            + "first also covers the second, and it could drain a controller it was "
                            + "never meant to reach. Rename one");
                }
            }
        }
    }

    private void work(final List<String> found) {
        if (work.isEmpty()) {
            found.add("No work is named, and an estate that guards nothing needs no piplex");
            return;
        }
        final Set<String> keys = new LinkedHashSet<>();
        for (final Work one : work) {
            if (one.key() == null || one.key().isBlank()) {
                found.add("A piece of work has no key, and that is what one build at a time is "
                        + "counted by");
                continue;
            }
            if (!fileSegment(one.key())) {
                found.add("Work '" + one.key() + "' is not one file name. Work keys become files "
                        + "under the generated pipelines directory, so they must not be absolute, "
                        + "'.', '..', or contain '/' or '\\'");
                continue;
            }
            if (!keys.add(one.key())) {
                found.add("Two pieces of work compete for '" + one.key() + "'. They are then one "
                        + "piece of work with two sets of guards, and which guards apply is chance");
            }
            work(found, one);
        }
    }

    private void work(final List<String> found, final Work one) {
        final String said = "Work '" + one.key() + "' ";
        if (one.designated() && one.follows()) {
            found.add(said + "names both a designation and an active key. They answer the same "
                    + "question -- who runs -- and piplex refuses a request carrying both");
        }
        if (!one.designated() && !one.follows()) {
            found.add(said + "names neither a designation nor an active key, so whoever takes the "
                    + "lease runs. Name which key decides where it runs");
        }
        if (one.follows() && one.activeKey().startsWith(Environment.ROOT)) {
            found.add(said + "follows '" + one.activeKey() + "', which is under '" + Environment.ROOT
                    + "', where piplex keeps its own records. It belongs to whoever writes it");
        }
        if (!one.switched()) {
            // Every controller gets a drain job, and the drain reaches only work that names a switch.
            // So this work keeps running on a controller somebody has been told is empty.
            found.add(said + "names no switch, so nothing can stop it: not the stop job, and not a "
                    + "drain. A controller reported drained goes on running it. Name one");
        }
    }

    private void identity(final List<String> found) {
        if (operator != null && operator.apart() && grants == Grants.PER_ENVIRONMENT) {
            // Grants add; a longer prefix does not override a shorter one. So a controller granted
            // the whole environment holds C on designated/ and enabled/ however the operator is
            // granted, and the separation exists only in the file.
            found.add("The operator is given an identity of its own, and every controller is granted "
                    + "the whole environment. Grants add rather than narrow, so the controllers keep "
                    + "the very writes the operator was separated to hold, and nothing is separated. "
                    + "Name the keys instead");
        }
        if (operator != null && operator.apart() && !named(operator.credentialsId())) {
            found.add("The operator is given the identity '" + operator.clientId() + "', and no "
                    + "Jenkins credential is named to carry it. The jobs would act as the "
                    + "controller instead, and every handover and stop would fail on an ACL refusal");
        }
        if (operator != null && operator.apart() && transport == Transport.ALLOWALL) {
            found.add("The operator is given an identity of its own, but this cluster admits clients "
                    + "without proving who they are. A second identity there separates nothing, and "
                    + "the plugin refuses to use one");
        }
    }

    /** @return whether any controller writes a per-owner switch under its own identity */
    private boolean drainsItself() {
        return operator == null || !operator.apart();
    }

    private static boolean named(final String value) {
        return value != null && !value.isBlank();
    }

    private static boolean fileSegment(final String value) {
        return !".".equals(value) && !"..".equals(value)
                && value.indexOf('/') < 0 && value.indexOf('\\') < 0;
    }
}
