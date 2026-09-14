/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import hudson.AbortException;
import hudson.util.Secret;
import io.github.green4j.discas.common.transport.security.ClientSecurityProvider;
import io.github.green4j.discas.common.transport.security.PlaintextClientSecurity;
import io.github.green4j.discas.common.transport.tls.TlsClientSecurityProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * How the three security settings turn into the thing every connection is wrapped in.
 *
 * <p>Worth asserting here rather than anywhere else: what this produces is otherwise observable only
 * by watching bytes leave a controller, and "it was plaintext all along" is not a thing to find out
 * that way.
 */
@WithJenkins
class PiplexSecurityTest {

    @TempDir
    private Path directory;

    @Test
    void connectsInClearWhenNothingAsksOtherwise(final JenkinsRule jenkins) throws Exception {
        final PiplexConfiguration configuration = PiplexConfiguration.get();

        // The cluster in the quick start: a private network, discas in allowall.
        assertSame(PlaintextClientSecurity.PROVIDER, configuration.security());
    }

    @Test
    void refusesToConnectInClearWithStoresFilledIn(final JenkinsRule jenkins) {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setTls(false);
        configuration.setTlsTruststore(directory.resolve("client-ca.p12").toString());

        // Somebody who filled a trust store in believes this connection is encrypted. Quietly using
        // the store as decoration, or quietly ignoring the unticked box, both end with a token going
        // out in clear and nothing saying so.
        final AbortException refused = assertThrows(AbortException.class, configuration::security);

        assertTrue(refused.getMessage().contains("TLS is off"), refused.getMessage());
        assertTrue(refused.getMessage().contains("Manage Jenkins"), refused.getMessage());
    }

    @Test
    void authenticatesTheNodeOnlyWhenNoKeyStoreIsConfigured(final JenkinsRule jenkins) throws Exception {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setTls(true);

        // TLS against the JVM's own trust store, presenting nothing. What a token cluster needs, and
        // what an mtls one will turn away at the handshake.
        final ClientSecurityProvider security = configuration.security();

        assertInstanceOf(TlsClientSecurityProvider.class, security);
        assertNotSame(PlaintextClientSecurity.PROVIDER, security);
    }

    @Test
    void presentsThisControllersCertificateWhenAKeyStoreIsConfigured(final JenkinsRule jenkins)
            throws Exception {
        final Path keystore = keyStoreHolding("euc1-blue", "changeit");
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setTls(true);
        configuration.setTlsKeystore(keystore.toString());
        configuration.setTlsKeystorePassword(Secret.fromString("changeit"));

        assertInstanceOf(TlsClientSecurityProvider.class, configuration.security());
    }

    @Test
    void namesTheFileWhenAStoreIsNotThere(final JenkinsRule jenkins) {
        final Path missing = directory.resolve("nobody-put-this-here.p12");
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setTls(true);
        configuration.setTlsTruststore(missing.toString());

        final AbortException refused = assertThrows(AbortException.class, configuration::security);

        // The two commonest failures are a file the controller cannot read and a password that does
        // not open it. One of them is answered by looking at the file, so say which file.
        assertTrue(refused.getMessage().contains(missing.toString()), refused.getMessage());
        assertTrue(refused.getMessage().contains("trust store"), refused.getMessage());
    }

    @Test
    void namesTheFileWhenItIsNotAKeyStoreAtAll(final JenkinsRule jenkins) throws Exception {
        final Path notAStore = directory.resolve("README.txt");
        Files.writeString(notAStore, "the certificate is in the other directory", StandardCharsets.UTF_8);
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setTls(true);
        configuration.setTlsKeystore(notAStore.toString());
        configuration.setTlsKeystorePassword(Secret.fromString("changeit"));

        final AbortException refused = assertThrows(AbortException.class, configuration::security);

        assertTrue(refused.getMessage().contains(notAStore.toString()), refused.getMessage());
        assertTrue(refused.getMessage().contains("key store"), refused.getMessage());
    }

    @Test
    void refusesAKeyStoreItsPasswordDoesNotOpen(final JenkinsRule jenkins) throws Exception {
        final Path keystore = keyStoreHolding("euc1-blue", "changeit");
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setTls(true);
        configuration.setTlsKeystore(keystore.toString());
        configuration.setTlsKeystorePassword(Secret.fromString("not-the-password"));

        final AbortException refused = assertThrows(AbortException.class, configuration::security);

        // A stack trace ending in UnrecoverableKeyException is not what somebody reading a failed
        // build needs to be told.
        assertTrue(refused.getMessage().contains(keystore.toString()), refused.getMessage());
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
        final Path keystore = keyStoreHolding("euc1-blue", "changeit");
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setTls(true);
        configuration.setTlsKeystore(keystore.toString());
        configuration.setTlsKeystorePassword(Secret.fromString("changeit"));
        assertInstanceOf(TlsClientSecurityProvider.class, configuration.security());

        // A rotated certificate is a file replaced on disk. Nothing here holds the old one open, so
        // the next store built reads what is there now.
        Files.writeString(keystore, "rotated into something unreadable", StandardCharsets.UTF_8);

        assertThrows(AbortException.class, configuration::security);
    }

    /**
     * A PKCS12 file with one certificate and key in it, made by keytool.
     *
     * <p>By keytool rather than assembled here, because that is what an operator will point these
     * settings at. A file this test built to its own taste would prove that the plugin reads files
     * this test writes.
     *
     * @param alias    the certificate's alias, also its CN
     * @param password the store's password, and the key's
     * @return the file written
     * @throws Exception if keytool cannot be run
     */
    private Path keyStoreHolding(final String alias, final String password) throws Exception {
        final Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        assumeTrue(Files.isExecutable(keytool), "no keytool in this JDK");

        final Path file = directory.resolve(alias + ".p12");
        final Process keytoolRun = new ProcessBuilder(
                keytool.toString(),
                "-genkeypair",
                "-alias", alias,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "1",
                "-dname", "CN=" + alias,
                "-storetype", "PKCS12",
                "-keystore", file.toString(),
                "-storepass", password,
                "-keypass", password)
                .redirectErrorStream(true)
                .start();
        final String said = new String(keytoolRun.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, keytoolRun.waitFor(), said);
        return file;
    }
}
