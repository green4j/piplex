/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PKCS12 files made by keytool.
 *
 * <p>By keytool rather than assembled here, because that is what an operator will point the settings at.
 * A file a test built to its own taste would prove that the plugin reads files the test writes.
 */
final class KeyStores {

    private KeyStores() {
    }

    /**
     * @param directory where to write it
     * @param alias     the certificate's alias, also its CN
     * @param password  the store's password, and the key's
     * @return a store with one certificate and key in it
     * @throws Exception if keytool cannot be run
     */
    static Path keyStoreHolding(final Path directory, final String alias, final String password) throws Exception {
        return keyStoreHolding(directory, alias, password, "CN=" + alias, null);
    }

    /**
     * @param directory where to write it
     * @param alias     the certificate's alias
     * @param password  the store's password, and the key's
     * @param dname     the subject to issue it for
     * @param san       a subject alternative name, e.g. {@code dns:n1.discas.internal}, or {@code null}
     * @return a store with one certificate and key in it
     * @throws Exception if keytool cannot be run
     */
    static Path keyStoreHolding(final Path directory,
                                final String alias,
                                final String password,
                                final String dname,
                                final String san) throws Exception {
        final Path file = directory.resolve(alias + ".p12");
        final List<String> command = new ArrayList<>(List.of(
                keytool().toString(),
                "-genkeypair",
                "-alias", alias,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "1",
                "-dname", dname,
                "-storetype", "PKCS12",
                "-keystore", file.toString(),
                "-storepass", password,
                "-keypass", password));
        if (san != null) {
            command.addAll(List.of("-ext", "SAN=" + san));
        }
        run(command);
        return file;
    }

    /**
     * A store with a certificate in it and no key -- what an operator building one from the nodes' own
     * certificates ends up with.
     *
     * @param directory where to write it
     * @param alias     the certificate's alias, also its CN
     * @param password  the store's password
     * @return the file written
     * @throws Exception if keytool cannot be run
     */
    static Path trustStoreHolding(final Path directory, final String alias, final String password) throws Exception {
        final Path from = keyStoreHolding(directory, alias, password);
        final Path certificate = directory.resolve(alias + ".pem");
        run(List.of(keytool().toString(), "-exportcert", "-rfc",
                "-alias", alias, "-keystore", from.toString(), "-storepass", password,
                "-file", certificate.toString()));

        final Path file = directory.resolve(alias + "-trust.p12");
        run(List.of(keytool().toString(), "-importcert", "-noprompt",
                "-alias", alias, "-file", certificate.toString(),
                "-storetype", "PKCS12", "-keystore", file.toString(), "-storepass", password));
        return file;
    }

    static X509Certificate certificateIn(final Path file,
                                         final String password,
                                         final String alias) throws Exception {
        final KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream bytes = Files.newInputStream(file)) {
            store.load(bytes, password.toCharArray());
        }
        return (X509Certificate) store.getCertificate(alias);
    }

    private static Path keytool() {
        final Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        assumeTrue(Files.isExecutable(keytool), "No keytool in this JDK");
        return keytool;
    }

    private static void run(final List<String> command) throws Exception {
        final Process keytool = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String said = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, keytool.waitFor(), said);
    }
}
