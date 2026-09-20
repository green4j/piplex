/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * One discas node, from the published image, configured the way the documentation says to configure
 * one.
 *
 * <p>A container rather than a node built in this JVM, and the difference is the point. What this
 * plugin needs from a node is enforcement -- who a client is, and which keys it may touch -- and none
 * of that lives in this repository. Built here against the library, the wiring under test would be
 * this test's own; run from the image, it is the artifact an operator deploys, reached through the
 * same {@code --client-auth}, {@code --client-acl-file} and {@code --client-tls-*} flags
 * {@code docs/07-discas.md} tells them to pass.
 *
 * <p>Which is what makes an ACL template testable at all. {@code work-narrowed.conf} shipped granting
 * the controllers {@code G} where the jobs beside it needed {@code C}, and every test in this module
 * passed, because until now nothing here had ever refused a write.
 *
 * <p>One node, and no peers: piplex asks a cluster for linearizable reads and compare-and-set, which
 * one node answers as well as five. Consensus across several is discas's own to test, and is tested
 * there.
 */
final class DiscasCluster implements AutoCloseable {

    /** Where the node listens inside the container, as the image's entrypoint defaults it. */
    private static final int CLIENT_PORT = 7002;
    private static final int PEER_PORT = 7001;
    // The version comes from the build, so the node run here and the client the plugin links cannot
    // drift apart.
    private static final String IMAGE = "green4j/discas:" + version();

    private static final String NODE = "1";
    private static final String CLUSTER = "piplex-it";
    private static final String ACL_PATH = "/etc/discas/acl.conf";
    private static final String KEYSTORE_PATH = "/etc/discas/node.p12";
    private static final String TRUSTSTORE_PATH = "/etc/discas/trust.p12";
    private static final String TOKENS_PATH = "/etc/discas/tokens.conf";

    private final GenericContainer<?> container;

    private DiscasCluster(final GenericContainer<?> container) {
        this.container = container;
    }

    /**
     * @return the discas version this build carries, which the intTest task passes down
     */
    private static String version() {
        final String named = System.getProperty("piplex.discasVersion");
        if (named == null || named.isBlank()) {
            throw new IllegalStateException("piplex.discasVersion is unset: run ':piplex-jenkins:intTest'");
        }
        return named;
    }

    /**
     * @param acl the ACL file the node enforces, as {@code --client-acl-file} takes it
     * @return a node enforcing it and admitting anybody, to be told who a client is by a method below
     */
    static Builder enforcing(final Path acl) {
        return new Builder(acl);
    }

    /**
     * @return where this controller should be pointed, as the nodes field takes it
     */
    String nodes() {
        // The mapped port, not the one inside: the controller reaches it from the host.
        return NODE + "=" + container.getHost() + ":" + container.getMappedPort(CLIENT_PORT);
    }

    @Override
    public void close() {
        container.stop();
    }

    /**
     * The flags one node is started with, named one at a time.
     *
     * <p>Spelled out rather than defaulted, because the flags are half of what is being tested: a
     * template that works only against a node this test configured leniently is a template that
     * works nowhere.
     */
    static final class Builder {

        private final Path acl;
        private Path tokens;
        private Path keyStore;
        private Path trustStore;
        private String password;
        private String auth = "allowall";

        private Builder(final Path acl) {
            this.acl = acl;
        }

        /**
         * @param file     the token store, hashes and all, as {@code --client-token-file} takes it
         * @param keystore what the node presents on the client port
         * @param trust    who it accepts a client certificate from
         * @param secret   the password to both stores
         * @return this
         */
        Builder tokenAuth(final Path file, final Path keystore, final Path trust, final String secret) {
            this.auth = "token";
            this.tokens = file;
            return tls(keystore, trust, secret);
        }

        /**
         * @param keystore what the node presents on the client port
         * @param trust    who it accepts a client certificate from
         * @param secret   the password to both stores
         * @return this
         */
        Builder mutualTls(final Path keystore, final Path trust, final String secret) {
            this.auth = "mtls";
            return tls(keystore, trust, secret);
        }

        private Builder tls(final Path keystore, final Path trust, final String secret) {
            this.keyStore = keystore;
            this.trustStore = trust;
            this.password = secret;
            return this;
        }

        /**
         * @return the node, once it is listening
         */
        DiscasCluster start() {
            final List<String> command = new ArrayList<>(List.of(
                    "node",
                    "--node-id", NODE,
                    "--cluster-id", CLUSTER,
                    "--cluster-size", "1",
                    "--members", NODE + "=127.0.0.1:" + PEER_PORT,
                    "--client-bind", "0.0.0.0:" + CLIENT_PORT,
                    "--client-acl-file", ACL_PATH,
                    "--client-auth", auth));
            GenericContainer<?> node = new GenericContainer<>(
                    DockerImageName.parse(IMAGE))
                    .withCopyFileToContainer(MountableFile.forHostPath(acl), ACL_PATH)
                    .withExposedPorts(CLIENT_PORT)
                    // Its own line in the log, and the last one the node writes before it serves.
                    .waitingFor(Wait.forLogMessage(".*node " + NODE + " started.*", 1)
                            .withStartupTimeout(Duration.ofMinutes(2)));
            if (tokens != null) {
                node = node.withCopyFileToContainer(MountableFile.forHostPath(tokens), TOKENS_PATH);
                command.addAll(List.of("--client-token-file", TOKENS_PATH));
            }
            if (keyStore != null) {
                node = node.withCopyFileToContainer(
                                MountableFile.forHostPath(keyStore), KEYSTORE_PATH)
                        .withCopyFileToContainer(
                                MountableFile.forHostPath(trustStore), TRUSTSTORE_PATH);
                command.addAll(List.of(
                        "--client-tls",
                        "--client-tls-keystore", KEYSTORE_PATH,
                        "--client-tls-keystore-password", password,
                        "--client-tls-truststore", TRUSTSTORE_PATH,
                        "--client-tls-truststore-password", password));
            }
            final GenericContainer<?> started = node.withCommand(command.toArray(new String[0]));
            started.start();
            return new DiscasCluster(started);
        }
    }
}
