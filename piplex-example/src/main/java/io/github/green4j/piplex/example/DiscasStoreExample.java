/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.example;

import io.github.green4j.discas.client.DisCasClient;
import io.github.green4j.discas.client.DisCasClientFactory;
import io.github.green4j.discas.client.transport.TcpClientBootstrap;
import io.github.green4j.discas.common.client.ClientTransportConfig;
import io.github.green4j.discas.common.client.ReadConsistency;
import io.github.green4j.discas.common.identity.ClientId;
import io.github.green4j.discas.common.identity.NodeId;
import io.github.green4j.piplex.Piplex;
import io.github.green4j.piplex.discas.DiscasCoordinationStore;
import io.github.green4j.piplex.exclusive.Designation;
import io.github.green4j.piplex.exclusive.Designations;
import io.github.green4j.piplex.milestone.Milestone;
import io.github.green4j.piplex.milestone.Milestones;
import io.github.green4j.piplex.store.CoordinationStore;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The same primitives against a real cluster, which is the only thing the other examples fake.
 *
 * <p>There is nothing to it, and that is the point: one line changes. Everything else -- the primitives,
 * the requests, the decisions -- is the code from the other examples, because the store was always an
 * argument. Nothing is discovered and nothing is configured by a service file; the implementation is
 * named here, once, and handed in.
 *
 * <p>Two choices below are deliberate and belong in any real wiring.
 *
 * <p>Each controller connects with its <b>own</b> {@link ClientId}. Prefix ACLs are granted per client,
 * and a store-side audit record is only worth keeping if it names one controller. Sharing an identity --
 * or going through an HTTP agent, which does not authenticate its callers -- merges all four into one
 * and throws attribution away.
 *
 * <p>Watches read {@link ReadConsistency#LINEARIZABLE}, because what a watch returns here is acted on:
 * a changed designation stops a running job, and a stale read would stop the wrong one.
 *
 * <p>It only reads. Start a cluster first -- the quickstart in the discas repository does it in three
 * commands -- then:
 *
 * <pre>
 * ./gradlew :piplex-example:run -PmainClass=DiscasStoreExample \
 *         --args="euc1-blue eod n1=127.0.0.1:7101 n2=127.0.0.1:7102 n3=127.0.0.1:7103"
 * </pre>
 *
 * <p>Those are the nodes' <b>client</b> ports, which is what {@code --client-bind} sets and what every
 * {@code nodes} setting in the guides names. The port in {@code --members} is the one the nodes reach
 * each other on, and a client pointed at it finds a consensus port that will not talk to it.
 */
public final class DiscasStoreExample {

    private DiscasStoreExample() {
    }

    /**
     * @param args the client id, the key to look at, then one {@code nodeId=host:port} per node
     */
    public static void main(final String[] args) {
        if (args.length < 3) {
            Examples.say("Usage: DiscasStoreExample <clientId> <key> <nodeId=host:port>...");
            return;
        }
        final ClientId clientId = ClientId.of(args[0]);
        final String key = args[1];

        final DisCasClient client = DisCasClientFactory.create(
                clientId, new TcpClientBootstrap(nodes(args), ClientTransportConfig.defaults()));

        // The store owns the client from here: closing one closes the other, so a caller cannot leave
        // a connection behind by forgetting which of the two it was holding.
        try (CoordinationStore store = new DiscasCoordinationStore(
                client, ReadConsistency.LINEARIZABLE, true);
                Controllers controllers = new Controllers(store)) {

            final Piplex piplex = controllers.controller(clientId.value());

            Examples.say("=== " + key + " on the cluster, as " + clientId + " ===");

            final Designation designated = Examples.await(piplex.designations().current(key));
            Examples.say(Designations.keyOf(key) + " -> "
                    + (designated == null ? "nobody designated yet" : describe(designated)));

            final Milestone milestone = Examples.await(piplex.milestones().current(key));
            Examples.say(Milestones.keyOf(key) + " -> "
                    + (milestone == null ? "nothing published yet" : describe(milestone)));
        }
    }

    private static Map<NodeId, InetSocketAddress> nodes(final String[] args) {
        final Map<NodeId, InetSocketAddress> nodes = new LinkedHashMap<>();
        for (int i = 2; i < args.length; i++) {
            final String entry = args[i];
            final int equals = entry.indexOf('=');
            // The last colon rather than every one, and brackets unwrapped: an IPv6 literal is mostly
            // colons, and bracketed is the only way to write one a host:port can be read out of. The
            // same reading as the Jenkins settings field, so one form of an address is documented.
            final int colon = entry.lastIndexOf(':');
            if (equals < 1 || colon < equals + 2 || colon == entry.length() - 1
                    || colon < entry.lastIndexOf(']')) {
                throw new IllegalArgumentException("Expected nodeId=host:port, got: " + entry);
            }
            final String host = entry.substring(equals + 1, colon);
            nodes.put(NodeId.of(entry.substring(0, equals)),
                    new InetSocketAddress(host.startsWith("[") && host.endsWith("]")
                                    ? host.substring(1, host.length() - 1) : host,
                            Integer.parseInt(entry.substring(colon + 1))));
        }
        return nodes;
    }

    private static String describe(final Designation designation) {
        return designation.owner() + " (change #" + designation.seq()
                + ", " + designation.reason() + " at " + designation.at() + ")";
    }

    private static String describe(final Milestone milestone) {
        return milestone.generation() + " (by " + milestone.by()
                + " in run " + milestone.runId() + " at " + milestone.at() + ")";
    }
}
