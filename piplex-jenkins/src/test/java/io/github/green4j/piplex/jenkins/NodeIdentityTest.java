/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import java.net.Socket;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a certificate is that of a node this controller was configured to talk to.
 *
 * <p>Trust in the chain is the JVM's work and not what is under test: every chain is trusted here, so
 * what a row sees is the identity check alone.
 */
class NodeIdentityTest {

    @TempDir
    private Path directory;

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "-", value = {
        // Self-signed by keytool for one host, as a cluster without a CA of its own has: the common name
        // is all it says.
        "CN=n1            | -                              | n1, 10.0.0.1                 | true  | -",
        // Both lists in the message: a certificate for the wrong host and nodes configured under an
        // address their certificates do not carry look identical without them.
        "CN=build-cache   | -                              | n1, 10.0.0.1                 | false | build-cache, n1",
        // With an alternative name, that is where everything it claims is, and the common name is
        // decoration.
        "CN=discas node 1 | dns:n1.discas.internal         | n1.discas.internal, 10.0.0.1 | true  | -",
        "CN=discas node 1 | dns:n1.discas.internal         | discas node 1                | false | -",
        // An address is matched only as an address: not by a wildcard, not by a DNS name spelling it.
        "CN=wild          | dns:*.0.0.1                    | n1, 10.0.0.1                 | false | -",
        "CN=spelled       | dns:10.0.0.1                   | n1, 10.0.0.1                 | false | -",
        "CN=by-ip         | ip:10.0.0.1,ip:::1             | n1, 10.0.0.1                 | true  | -",
        // The certificate spells ::1 as 0:0:0:0:0:0:0:1, and it is the same address.
        "CN=by-ip         | ip:10.0.0.1,ip:::1             | n1, ::1                      | true  | -",
        "CN=wild          | dns:*.discas.internal          | n1, n1.discas.internal       | true  | -",
        // An alternative name nothing can be recognised by still says where the names are kept, so the
        // common name is not read.
        "CN=n1            | uri:https://n1.discas.internal | n1, 10.0.0.1                 | false | n1",
    })
    void acceptsOnlyACertificateWhichNamesAConfiguredNode(final String subject,
                                                          final String alternativeName,
                                                          final String nodes,
                                                          final boolean accepted,
                                                          final String named) throws Exception {
        final X509Certificate[] presented = {KeyStores.certificateIn(
                KeyStores.keyStoreHolding(directory, "node", "changeit", subject, alternativeName),
                "changeit", "node")};
        final X509ExtendedTrustManager checked = (X509ExtendedTrustManager) NodeIdentity.checkedAgainst(
                new TrustManager[]{new TrustsEveryChain()}, Set.of(nodes.split(",\\s*")))[0];

        if (accepted) {
            checked.checkServerTrusted(presented, "RSA");
            return;
        }
        final CertificateException refused =
                assertThrows(CertificateException.class, () -> checked.checkServerTrusted(presented, "RSA"));
        if (named != null) {
            for (final String name : named.split(",\\s*")) {
                assertTrue(refused.getMessage().contains(name), refused.getMessage());
            }
        }
    }

    /**
     * Says yes to every chain.
     */
    private static final class TrustsEveryChain extends X509ExtendedTrustManager {

        @Override
        public void checkClientTrusted(final X509Certificate[] chain, final String authType) {
        }

        @Override
        public void checkClientTrusted(final X509Certificate[] chain,
                                       final String authType,
                                       final Socket socket) {
        }

        @Override
        public void checkClientTrusted(final X509Certificate[] chain,
                                       final String authType,
                                       final SSLEngine engine) {
        }

        @Override
        public void checkServerTrusted(final X509Certificate[] chain, final String authType) {
        }

        @Override
        public void checkServerTrusted(final X509Certificate[] chain,
                                       final String authType,
                                       final Socket socket) {
        }

        @Override
        public void checkServerTrusted(final X509Certificate[] chain,
                                       final String authType,
                                       final SSLEngine engine) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
