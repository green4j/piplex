/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import hudson.AbortException;
import hudson.util.Secret;
import io.github.green4j.discas.common.transport.security.PlaintextClientSecurity;
import io.github.green4j.discas.common.transport.tls.TlsClientSecurityProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the three security settings turn into the thing every connection is wrapped in.
 *
 * <p>Worth asserting here rather than anywhere else: what this produces is otherwise observable only
 * by watching bytes leave a controller, and "it was plaintext all along" is not a thing to find out
 * that way.
 */
@WithJenkins
class PiplexSecurityTest {

    // With TLS on, a node's certificate is checked against the nodes this controller was configured
    // with, so the settings that turn TLS on need those as well.
    private static final String CLUSTER = "n1=10.0.0.1:7101\nn2=10.0.0.2:7101";

    @TempDir
    private Path directory;

    @Test
    void refusesSettingsItCannotConnectWithSafely(final JenkinsRule jenkins) throws Exception {
        final Path keystore = KeyStores.keyStoreHolding(directory, "euc1-blue", "changeit");
        final Path missing = directory.resolve("nobody-put-this-here.p12");
        final Path notAStore = directory.resolve("README.txt");
        Files.writeString(notAStore, "the certificate is in the other directory", StandardCharsets.UTF_8);

        final List<Refused> table = List.of(
                // Somebody who filled a trust store in believes this connection is encrypted.
                new Refused(c -> c.setTlsTruststore(missing.toString()), "TLS is off", "Manage Jenkins"),
                // The token is this controller's password to the whole cluster, and no setting sends it in
                // clear.
                new Refused(c -> c.setToken(Secret.fromString("s3cret")), "in clear", "Connect over TLS"),
                // A node admits clients one way at a time, and which one is meant cannot be told from here.
                new Refused(c -> {
                    c.setTls(true);
                    keyStore(c, keystore, "changeit");
                    c.setToken(Secret.fromString("s3cret"));
                }, "--client-auth token", "--client-auth mtls"),
                // Nothing pinned and no identity check: any CA in the JVM's store vouches for whoever
                // answers, and the first thing this controller does is hand them the token.
                new Refused(c -> {
                    c.setTls(true);
                    c.setTlsVerifyNodeIdentity(false);
                }, "any CA this JVM trusts", "trust store"),
                // The commonest failures: a file the controller cannot read, and a password that does not
                // open it. Both are answered by looking at the file, so the file is named.
                new Refused(c -> {
                    c.setTls(true);
                    c.setTlsTruststore(missing.toString());
                }, missing.toString(), "trust store"),
                new Refused(c -> {
                    c.setTls(true);
                    keyStore(c, notAStore, "changeit");
                }, notAStore.toString(), "key store"),
                new Refused(c -> {
                    c.setTls(true);
                    keyStore(c, keystore, "not-the-password");
                }, keystore.toString()));

        for (final Refused row : table) {
            final PiplexConfiguration configuration = cleared();
            row.settings().accept(configuration);

            final AbortException refused = assertThrows(AbortException.class, configuration::security);

            for (final String said : row.said()) {
                assertTrue(refused.getMessage().contains(said), refused.getMessage());
            }
        }
    }

    @Test
    void wrapsEveryConnectionTheWayTheSettingsSay(final JenkinsRule jenkins) throws Exception {
        final Path keystore = KeyStores.keyStoreHolding(directory, "euc1-blue", "changeit");
        final Path truststore = KeyStores.trustStoreHolding(directory, "n1", "changeit");

        // The cluster in the quick start: a private network, discas in allowall.
        assertSame(PlaintextClientSecurity.PROVIDER, cleared().security());

        // TLS against the JVM's own trust store, presenting nothing: what a token cluster needs.
        final PiplexConfiguration tls = cleared();
        tls.setTls(true);
        assertInstanceOf(TlsClientSecurityProvider.class, tls.security());

        // Presenting this controller's certificate, which is what an mtls cluster needs.
        final PiplexConfiguration mtls = cleared();
        mtls.setTls(true);
        keyStore(mtls, keystore, "changeit");
        assertInstanceOf(TlsClientSecurityProvider.class, mtls.security());

        // A trust store holding the nodes' own certificates is a coarser test of identity of its own, so
        // a cluster whose certificates name nothing this controller can match is not stuck.
        final PiplexConfiguration pinned = cleared();
        pinned.setTls(true);
        pinned.setTlsTruststore(truststore.toString());
        pinned.setTlsTruststorePassword(Secret.fromString("changeit"));
        pinned.setTlsVerifyNodeIdentity(false);
        assertInstanceOf(TlsClientSecurityProvider.class, pinned.security());
    }

    @Test
    void readsAClearedSecretAsNoneRatherThanAsAnEmptyOne(final JenkinsRule jenkins) {
        final PiplexConfiguration configuration = PiplexConfiguration.get();

        configuration.setToken(Secret.fromString("s3cret"));
        assertEquals("s3cret", configuration.getToken().getPlainText());

        // A form posts a cleared password as an empty Secret. An empty token sent to a cluster is a
        // failed handshake, not the "no token configured" that clearing the field meant.
        configuration.setToken(Secret.fromString(""));
        assertNull(configuration.getToken());

        configuration.setTlsKeystorePassword(Secret.fromString(""));
        assertNull(configuration.getTlsKeystorePassword());
    }

    @Test
    void picksUpAStoreRewrittenUnderIt(final JenkinsRule jenkins) throws Exception {
        final Path keystore = KeyStores.keyStoreHolding(directory, "euc1-blue", "changeit");
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setTls(true);
        configuration.setNodes(CLUSTER);
        keyStore(configuration, keystore, "changeit");
        assertInstanceOf(TlsClientSecurityProvider.class, configuration.security());

        // A rotated certificate is a file replaced on disk. Nothing here holds the old one open, so
        // the next store built reads what is there now.
        Files.writeString(keystore, "rotated into something unreadable", StandardCharsets.UTF_8);

        assertThrows(AbortException.class, configuration::security);
    }

    /**
     * @return the settings as nothing had been set, but for the cluster
     */
    private static PiplexConfiguration cleared() {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setNodes(CLUSTER);
        configuration.setTls(false);
        configuration.setToken(null);
        configuration.setTlsKeystore(null);
        configuration.setTlsKeystorePassword(null);
        configuration.setTlsTruststore(null);
        configuration.setTlsTruststorePassword(null);
        configuration.setTlsVerifyNodeIdentity(true);
        return configuration;
    }

    private static void keyStore(final PiplexConfiguration configuration, final Path file, final String password) {
        configuration.setTlsKeystore(file.toString());
        configuration.setTlsKeystorePassword(Secret.fromString(password));
    }

    /**
     * Settings a controller must not connect with, and what the refusal has to say.
     *
     * @param settings what an operator filled in
     * @param said     what the message names
     */
    private record Refused(Consumer<PiplexConfiguration> settings, String... said) {
    }
}
