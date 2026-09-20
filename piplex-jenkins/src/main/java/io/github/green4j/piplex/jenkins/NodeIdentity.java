/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks that the certificate a node presents is a certificate for one of the nodes this controller
 * was configured to talk to, and not merely one the trust store is willing to vouch for.
 *
 * <p>Without this, a valid chain is the whole test, and what that admits depends entirely on what
 * signed the nodes. Against a private CA that signs nothing else it is very nearly enough. Against the
 * JVM's default trust store it is not a test at all: any host on the path with a certificate from any
 * public CA answers as the cluster, and this controller hands it the token in the same breath.
 *
 * <p><b>What it binds, and what it does not.</b> The discas client mints its engines without telling
 * them which node they are dialling -- {@code ClientSecurityProvider.forOutbound()} takes no peer --
 * so the identity cannot be checked against the address this particular connection went to. What is
 * checked is membership: the certificate names one of the configured nodes, by node id or by host. A
 * node of the cluster answering for another node of the same cluster is therefore not caught here, and
 * it is a different threat from the one this is for -- every one of them is already trusted to hold the
 * data and to be told the token.
 *
 * <p>A certificate with a subject alternative name is read there and nowhere else, as the rules for
 * reading certificates have it. Only one without any falls back to its common name, which is what a
 * self-signed certificate made by hand for one host usually is, and letting it through is the whole
 * reason a cluster does not need a CA to be verified here.
 *
 * <p>An address is matched only as an address: by an IP alternative name, compared as bytes, or by a
 * common name that spells it exactly. A DNS name never matches one, wildcard or not -- {@code *.0.0.1}
 * is a name, and {@code 10.0.0.1} is not in its domain.
 */
final class NodeIdentity extends X509ExtendedTrustManager {

    private static final int DNS_NAME = 2;
    private static final int IP_ADDRESS = 7;
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");

    private final X509ExtendedTrustManager delegate;
    private final Set<String> nodes;
    private final Set<String> names;
    private final Set<InetAddress> addresses;

    private NodeIdentity(final X509ExtendedTrustManager delegate,
                         final Set<String> nodes,
                         final Set<String> names,
                         final Set<InetAddress> addresses) {
        this.delegate = delegate;
        this.nodes = nodes;
        this.names = names;
        this.addresses = addresses;
    }

    /**
     * Wraps whatever the trust store produced, so that the identity is checked after the chain is.
     *
     * @param delegates the trust managers the trust store produced
     * @param nodes     the node ids and hosts of the configured cluster
     * @return the same managers, each X.509 one now checking the identity too
     * @throws GeneralSecurityException if there is no X.509 trust manager among them, and so nothing
     *                                  the identity could be checked after
     */
    static TrustManager[] checkedAgainst(final TrustManager[] delegates, final Set<String> nodes)
            throws GeneralSecurityException {
        final Set<String> expected = new LinkedHashSet<>();
        final Set<String> names = new LinkedHashSet<>();
        final Set<InetAddress> addresses = new LinkedHashSet<>();
        for (final String node : nodes) {
            final String name = node.toLowerCase(Locale.ROOT);
            expected.add(name);
            final InetAddress address = literal(name);
            if (address == null) {
                names.add(name);
            } else {
                addresses.add(address);
            }
        }
        final TrustManager[] checked = delegates.clone();
        boolean any = false;
        for (int i = 0; i < checked.length; i++) {
            if (checked[i] instanceof X509ExtendedTrustManager x509) {
                checked[i] = new NodeIdentity(x509, expected, names, addresses);
                any = true;
            }
        }
        if (!any) {
            // Passing them through unwrapped would leave the check silently off, which is the state
            // this class exists to make impossible.
            throw new GeneralSecurityException(
                    "The configured trust material produced no X.509 trust manager, so a node's "
                            + "identity cannot be checked");
        }
        return checked;
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] chain, final String authType)
            throws CertificateException {
        delegate.checkServerTrusted(chain, authType);
        check(chain);
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] chain,
                                   final String authType,
                                   final Socket socket) throws CertificateException {
        delegate.checkServerTrusted(chain, authType, socket);
        check(chain);
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] chain,
                                   final String authType,
                                   final SSLEngine engine) throws CertificateException {
        delegate.checkServerTrusted(chain, authType, engine);
        check(chain);
    }

    // The client side of a connection is never a node, so there is no node identity to check on it.
    // A Jenkins controller does not accept discas client connections at all; these are here because
    // one provider mints the engines for both roles.

    @Override
    public void checkClientTrusted(final X509Certificate[] chain, final String authType)
            throws CertificateException {
        delegate.checkClientTrusted(chain, authType);
    }

    @Override
    public void checkClientTrusted(final X509Certificate[] chain,
                                   final String authType,
                                   final Socket socket) throws CertificateException {
        delegate.checkClientTrusted(chain, authType, socket);
    }

    @Override
    public void checkClientTrusted(final X509Certificate[] chain,
                                   final String authType,
                                   final SSLEngine engine) throws CertificateException {
        delegate.checkClientTrusted(chain, authType, engine);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }

    private void check(final X509Certificate[] chain) throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw new CertificateException("The node presented no certificate");
        }
        final List<Presented> presented = namesIn(chain[0]);
        for (final Presented name : presented) {
            if (names(name)) {
                return;
            }
        }
        // Both lists, because the two mistakes this catches look identical without them: a certificate
        // issued for the wrong host, and a cluster whose nodes are named in the settings by an address
        // their certificates do not carry.
        throw new CertificateException("The node presented a certificate for " + presented
                + ", which is none of the configured discas nodes " + nodes
                + ". A node is recognised by its node id or by the host it was configured under, in "
                + "the certificate's subject alternative name -- or in its common name when it has no "
                + "alternative name at all");
    }

    private boolean names(final Presented presented) {
        final String name = presented.name().toLowerCase(Locale.ROOT);
        final InetAddress address = literal(name);
        return switch (presented.kind()) {
            case IP -> address != null && addresses.contains(address);
            case COMMON -> address == null ? names.contains(name) : addresses.contains(address);
            case DNS -> address == null && (names.contains(name) || wildcardNames(name));
        };
    }

    private boolean wildcardNames(final String name) {
        if (!name.startsWith("*.")) {
            return false;
        }
        // One wildcard, matching one label, which is all a wildcard certificate is allowed to mean.
        final String domain = name.substring(1);
        for (final String node : names) {
            final int label = node.indexOf('.');
            if (label > 0 && node.substring(label).equals(domain)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads an IP address written out, without asking DNS about anything.
     *
     * @param text a name or an address
     * @return the address, or {@code null} when the text is not one
     */
    static InetAddress literal(final String text) {
        try {
            final Matcher v4 = IPV4.matcher(text);
            if (v4.matches()) {
                final byte[] bytes = new byte[4];
                for (int i = 0; i < 4; i++) {
                    final int octet = Integer.parseInt(v4.group(i + 1));
                    if (octet > 255) {
                        return null;
                    }
                    bytes[i] = (byte) octet;
                }
                return InetAddress.getByAddress(bytes);
            }
            if (text.indexOf(':') < 0) {
                return null;
            }
            // Bracketed, so that what does not parse is refused rather than looked up.
            return InetAddress.getByName(text.startsWith("[") ? text : "[" + text + "]");
        } catch (final UnknownHostException notAnAddress) {
            return null;
        }
    }

    private enum Kind { DNS, IP, COMMON }

    private record Presented(Kind kind, String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static List<Presented> namesIn(final X509Certificate leaf) throws CertificateParsingException {
        final List<Presented> names = new ArrayList<>();
        final Collection<List<?>> alternatives = leaf.getSubjectAlternativeNames();
        // Whether the certificate has any alternative name at all, which is a different question from
        // whether it has one this can read. A certificate naming itself by URI or by e-mail address and
        // nothing else has said where its names are kept, and falling back to a common name there would
        // accept a name the issuer did not put in the place names go.
        final boolean anyAlternative = alternatives != null && !alternatives.isEmpty();
        if (alternatives != null) {
            for (final List<?> alternative : alternatives) {
                if (alternative.size() < 2 || !(alternative.get(0) instanceof Integer type)) {
                    continue;
                }
                if (alternative.get(1) instanceof String name) {
                    if (type == DNS_NAME) {
                        names.add(new Presented(Kind.DNS, name));
                    } else if (type == IP_ADDRESS) {
                        names.add(new Presented(Kind.IP, name));
                    }
                }
            }
        }
        if (names.isEmpty() && !anyAlternative) {
            final String common = commonNameIn(leaf);
            if (common != null) {
                names.add(new Presented(Kind.COMMON, common));
            }
        }
        return names;
    }

    /**
     * @param leaf the certificate
     * @return the CN of its subject, or {@code null} when it has none
     */
    static String commonNameIn(final X509Certificate leaf) {
        try {
            for (final Rdn rdn : new LdapName(leaf.getSubjectX500Principal().getName()).getRdns()) {
                if ("cn".equalsIgnoreCase(rdn.getType())) {
                    return String.valueOf(rdn.getValue());
                }
            }
        } catch (final InvalidNameException notADistinguishedName) {
            return null;
        }
        return null;
    }
}
