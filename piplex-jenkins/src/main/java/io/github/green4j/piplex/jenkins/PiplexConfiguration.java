/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.Extension;
import hudson.model.TaskListener;
import hudson.util.Secret;
import io.github.green4j.discas.client.DisCasClient;
import io.github.green4j.discas.client.DisCasClientConfig;
import io.github.green4j.discas.client.DisCasClientFactory;
import io.github.green4j.discas.client.transport.TcpClientBootstrap;
import io.github.green4j.discas.common.client.ClientTransportConfig;
import io.github.green4j.discas.common.KvLimits;
import io.github.green4j.discas.common.client.ReadConsistency;
import io.github.green4j.discas.common.identity.ClientDescription;
import io.github.green4j.discas.common.identity.ClientId;
import io.github.green4j.discas.common.identity.NodeId;
import io.github.green4j.discas.common.transport.security.ClientSecurityProvider;
import io.github.green4j.discas.common.transport.security.PlaintextClientSecurity;
import io.github.green4j.discas.common.transport.tls.TlsClientSecurityProvider;
import io.github.green4j.discas.common.transport.tls.TlsConfig;
import io.github.green4j.discas.common.transport.tls.TlsContexts;
import io.github.green4j.piplex.Piplex;
import io.github.green4j.piplex.TimeSource;
import io.github.green4j.piplex.discas.DiscasCoordinationStore;
import io.github.green4j.piplex.observe.TextPiplexObserver;
import io.github.green4j.piplex.store.CoordinationStore;
import jenkins.model.GlobalConfiguration;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import javax.net.ssl.SSLContext;

/**
 * Where the one store this controller talks to is built, and the only place in the plugin that names an
 * implementation.
 *
 * <p>This is the composition root, and it is a plain {@code new}. Nothing is discovered: swapping the
 * store means editing {@link #build()}, which is a diff somebody can read, rather than a file on a
 * classpath that changes behaviour without appearing in any review.
 *
 * <p>{@code ownerId} is the setting that matters most. It is this controller's identity -- what a
 * designation names, what appears in every log line, and what a second controller must not also call
 * itself. It is not derivable: a Jenkins URL can change, a hostname is an implementation detail of
 * where it happens to run, and either one silently changing would hand the work to the wrong region.
 * So it is typed in, once, per controller.
 *
 * <p>The rest say how to reach the cluster, and how much of what it is told it should believe. A
 * discas node admits clients in one of three modes, and the settings here line up with them one for
 * one:
 *
 * <table>
 *   <caption>What to fill in for each of discas' client-auth modes</caption>
 *   <tr><th>{@code --client-auth}</th><th>Fill in</th><th>The client id is</th></tr>
 *   <tr><td>{@code allowall}</td><td>nothing</td><td>claimed, and nothing checks it</td></tr>
 *   <tr><td>{@code token}</td><td>{@code token}, and {@code tls} for it not to cross the wire in
 *       clear</td><td>still claimed; the token says the caller is one of us, not which one</td></tr>
 *   <tr><td>{@code mtls}</td><td>{@code tls}, {@code tlsKeystore}, {@code tlsTruststore}</td>
 *       <td>the certificate's subject, and nothing else</td></tr>
 * </table>
 *
 * <p>{@code watchPollPeriod} is the one setting here that is about cost rather than about identity. A
 * discas watch is a poll, and the client's own default of one second is right for work whose answer
 * changes by the minute. A nightly job parked for four hours waiting to be designated spends that
 * second over and over on a question whose answer changes once a quarter, so raising it is how the
 * standing cost of parking is made to match how often anything actually moves.
 *
 * <p>How watches <i>read</i> is not a setting, and that is a different decision from how often. Every
 * watch here is {@link ReadConsistency#LINEARIZABLE}, because the two an admitted run holds act on the
 * value the watch itself returned rather than reading again: a stale designation revokes the run late,
 * and late is precisely the window in which two controllers both believe they own the work. Polling
 * less often lengthens the same window, but by an amount that is written down in a field rather than
 * by however far behind the node that answered happens to be.
 *
 * <p>TLS is a checkbox rather than something inferred from a key store being filled in, and that is
 * deliberate. Inferring it means a path typed into the wrong field, or cleared while somebody was
 * looking at something else, silently downgrades every controller to plaintext and nothing says so.
 * Asked for explicitly, the same mistake is a build that will not start, which is the failure worth
 * having.
 *
 * <p>The store is built on first use and kept until a setting changes. Changing one <b>closes</b> it,
 * the discas client and the scheduler with it, and the next step builds a new one from the new
 * settings.
 *
 * <p>That has a cost worth stating plainly, because it is not the obvious behaviour: a run in flight is
 * holding that store, so its renewals stop and it is revoked once its grace period is out, exactly as
 * if the cluster had gone away. It is still the right way round. The alternative is a live connection
 * under an identity the operator has just changed, left open because something might still be using it
 * -- one more of them after every edit, none of them ever closed. Settings here are changed a handful
 * of times in the life of a controller, and the run that stops is one that would otherwise carry on
 * under a configuration that no longer exists.
 */
@Extension
@Symbol("piplex")
public final class PiplexConfiguration extends GlobalConfiguration {

    private String ownerId;
    private String clientId;
    private String nodes;

    private Secret token;
    private boolean tls;
    private String tlsKeystore;
    private Secret tlsKeystorePassword;
    private String tlsTruststore;
    private Secret tlsTruststorePassword;
    private String watchPollPeriod;

    private static volatile CoordinationStore supplied;
    private static volatile TimeSource suppliedTime;

    private transient ScheduledExecutorService scheduler;
    private transient TimeSource time;
    private transient CoordinationStore store;

    public PiplexConfiguration() {
        load();
    }

    /**
     * @return the singleton Jenkins keeps
     */
    public static PiplexConfiguration get() {
        return GlobalConfiguration.all().get(PiplexConfiguration.class);
    }

    /**
     * @return this controller's identity, as a designation would name it
     */
    public String getOwnerId() {
        return ownerId;
    }

    /**
     * @param value this controller's identity
     */
    @DataBoundSetter
    public void setOwnerId(final String value) {
        this.ownerId = trimmed(value);
        changed();
    }

    /**
     * @return the identity this controller connects to discas under
     */
    public String getClientId() {
        return clientId;
    }

    /**
     * @param value the identity to connect under; blank means the same as {@code ownerId}
     */
    @DataBoundSetter
    public void setClientId(final String value) {
        this.clientId = trimmed(value);
        changed();
    }

    /**
     * @return the cluster, as {@code nodeId=host:port} entries separated by commas or newlines
     */
    public String getNodes() {
        return nodes;
    }

    /**
     * @param value the cluster
     */
    @DataBoundSetter
    public void setNodes(final String value) {
        this.nodes = trimmed(value);
        changed();
    }

    /**
     * @return the shared token this controller authenticates with, or {@code null} for none
     */
    public Secret getToken() {
        return token;
    }

    /**
     * @param value the token a cluster running {@code --client-auth token} expects; blank means none
     */
    @DataBoundSetter
    public void setToken(final Secret value) {
        this.token = secret(value);
        changed();
    }

    /**
     * @return whether the connection to the cluster is TLS
     */
    public boolean isTls() {
        return tls;
    }

    /**
     * @param value whether to connect over TLS. Required by a cluster running
     *              {@code --client-tls} or {@code --client-auth mtls}
     */
    @DataBoundSetter
    public void setTls(final boolean value) {
        this.tls = value;
        changed();
    }

    /**
     * @return the PKCS12 file holding this controller's client certificate, or {@code null} for none
     */
    public String getTlsKeystore() {
        return tlsKeystore;
    }

    /**
     * @param value a PKCS12 file on this controller holding its client certificate and key. Required
     *              by a cluster running {@code --client-auth mtls}; blank means this controller
     *              presents no certificate and only the node is authenticated
     */
    @DataBoundSetter
    public void setTlsKeystore(final String value) {
        this.tlsKeystore = trimmed(value);
        changed();
    }

    /**
     * @return the password of {@link #getTlsKeystore()}, or {@code null} if it needs none
     */
    public Secret getTlsKeystorePassword() {
        return tlsKeystorePassword;
    }

    /**
     * @param value the key store's password, which is also taken as the private key's
     */
    @DataBoundSetter
    public void setTlsKeystorePassword(final Secret value) {
        this.tlsKeystorePassword = secret(value);
        changed();
    }

    /**
     * @return the PKCS12 file of CAs the nodes are checked against, or {@code null} for the JVM's own
     */
    public String getTlsTruststore() {
        return tlsTruststore;
    }

    /**
     * @param value a PKCS12 file holding the CA that signed the nodes' client-port certificates.
     *              Blank falls back to the JVM's default trust store, which is right only where a
     *              public CA signed them
     */
    @DataBoundSetter
    public void setTlsTruststore(final String value) {
        this.tlsTruststore = trimmed(value);
        changed();
    }

    /**
     * @return the password of {@link #getTlsTruststore()}, or {@code null} if it needs none
     */
    public Secret getTlsTruststorePassword() {
        return tlsTruststorePassword;
    }

    /**
     * @param value the trust store's password; most hold public certificates and have none
     */
    @DataBoundSetter
    public void setTlsTruststorePassword(final Secret value) {
        this.tlsTruststorePassword = secret(value);
        changed();
    }

    /**
     * @return the shortest gap between polls of a watch, or {@code null} for the client's own
     */
    public String getWatchPollPeriod() {
        return watchPollPeriod;
    }

    /**
     * @param value how long to wait after one poll of a watch answered before making the next, as
     *              {@code 30s}, {@code 2m} or ISO-8601. Blank leaves it to the discas client, which
     *              polls every second. At least {@code 500ms}
     */
    @DataBoundSetter
    public void setWatchPollPeriod(final String value) {
        this.watchPollPeriod = trimmed(value);
        changed();
    }

    @Override
    @NonNull
    public String getDisplayName() {
        return "piplex";
    }

    /**
     * The primitives, as this run should see them.
     *
     * <p>A {@link Piplex} per run rather than one for the controller, deliberately: they share the one
     * store, and what differs is where the decisions are written. Every line goes into the build log of
     * the run it is about, which is what lets an aggregator reconstruct one day's attempt across four
     * controllers from four build logs.
     *
     * @param listener the run's log
     * @return the primitives
     * @throws AbortException if the controller has not been configured
     */
    public Piplex piplexFor(final TaskListener listener) throws AbortException {
        return new Piplex(store(), time(),
                new TextPiplexObserver(line -> listener.getLogger().println("piplex: " + line)));
    }

    /**
     * @return this controller's identity
     * @throws AbortException if it has not been set
     */
    public String requireOwnerId() throws AbortException {
        if (ownerId == null) {
            throw new AbortException(
                    "piplex is not configured: set the controller's owner id in Manage Jenkins > System");
        }
        return ownerId;
    }

    /**
     * Uses a store built elsewhere, instead of building one from the settings.
     *
     * <p>For the tests in this package, which need the primitives without a cluster. It is a method
     * somebody calls rather than an implementation found on a classpath, so that what is in use is
     * visible at the call site -- the same reason {@link #build()} says {@code new} out loud.
     *
     * <p>Static, and that is not an accident: Jenkins rebuilds this object from disk on every restart,
     * while the store handed in outlives it. An instance field would be lost exactly where it is most
     * needed -- a run resuming after a restart asks for the store before any test code runs again.
     *
     * @param other     the store to use
     * @param otherTime where its time comes from
     */
    static void useStore(final CoordinationStore other, final TimeSource otherTime) {
        supplied = other;
        suppliedTime = otherTime;
    }

    private synchronized CoordinationStore store() throws AbortException {
        if (supplied != null) {
            return supplied;
        }
        if (store == null) {
            build();
        }
        return store;
    }

    private synchronized TimeSource time() throws AbortException {
        if (suppliedTime != null) {
            return suppliedTime;
        }
        if (store == null) {
            build();
        }
        return time;
    }

    private void build() throws AbortException {
        final Map<NodeId, InetSocketAddress> cluster = cluster();
        if (cluster.isEmpty()) {
            throw new AbortException(
                    "piplex is not configured: set the discas nodes in Manage Jenkins > System");
        }
        final ClientId identity = ClientId.of(clientId == null ? requireOwnerId() : clientId);
        final DisCasClient client = DisCasClientFactory.create(
                identity,
                description(),
                new TcpClientBootstrap(cluster, ClientTransportConfig.defaults(),
                        plainText(token), security()),
                DisCasClientConfig.defaults());
        scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            final Thread thread = new Thread(runnable, "piplex");
            thread.setDaemon(true);
            return thread;
        });
        time = TimeSource.of(scheduler);
        // Watches read linearizably because what they return is acted on rather than re-read: the two
        // an admitted run holds revoke it on the value the watch itself returned, so a stale one stops
        // the run late -- which is the overlap window this is here to keep narrow. It is not a setting
        // for that reason. What a nightly job wants instead is a longer poll period, which trades the
        // same latency for the same saving, but explicitly and without depending on how far behind a
        // node happens to be.
        store = new DiscasCoordinationStore(client, ReadConsistency.LINEARIZABLE, watchPollPeriod(), true);
    }

    /**
     * How long a watch waits between polls, or {@code null} to leave that to the discas client.
     *
     * <p>A watch in discas is a poll, not a subscription, and at one second -- the client's default --
     * an hour parked is thousands of consensus rounds per key to learn nothing. A job that runs once a
     * day does not need to hear about a designation within a second, so this is the setting that makes
     * the standing cost of parking match how often the answer actually changes.
     *
     * <p>What it buys back is bounded by something this cannot reach: a parked candidate looks again
     * every {@code renewEvery} anyway, so a period longer than that round only removes the polls
     * inside one round, never the round itself.
     *
     * @return the period, or {@code null}
     * @throws AbortException if what was typed is not a duration, or is below discas' floor
     */
    Duration watchPollPeriod() throws AbortException {
        if (watchPollPeriod == null) {
            return null;
        }
        final Duration period;
        try {
            period = Durations.parse(watchPollPeriod, null, "watchPollPeriod");
        } catch (final IllegalArgumentException notADuration) {
            throw new AbortException("piplex: " + notADuration.getMessage());
        }
        // Refused here rather than by the first watch hours later, and with the field named: only the
        // discas client's own configuration is allowed below this floor, and there is no field for it.
        if (period.compareTo(DisCasClient.MIN_WATCH_POLL_PERIOD) < 0) {
            throw new AbortException("piplex: watchPollPeriod must be at least "
                    + DisCasClient.MIN_WATCH_POLL_PERIOD.toMillis() + "ms, got '" + watchPollPeriod
                    + "'. Set it in Manage Jenkins > System, or leave it empty for the client's own");
        }
        return period;
    }

    /**
     * How the connection to the cluster is secured, from the three settings that say so.
     *
     * <p>Package-private, and tested directly: what it returns is otherwise observable only by
     * watching bytes leave a controller, which is exactly the wrong place to discover that TLS was
     * never on.
     *
     * @return the provider the transport wraps every connection in
     * @throws AbortException if TLS was asked for and the stores behind it cannot be loaded
     */
    ClientSecurityProvider security() throws AbortException {
        if (!tls) {
            // Nothing is silently ignored: a store filled in with the box unticked is somebody who
            // believes this connection is encrypted, and it is not.
            if (tlsKeystore != null || tlsTruststore != null) {
                throw new AbortException("piplex: a TLS key store or trust store is configured but TLS "
                        + "is off. Tick 'Connect over TLS' in Manage Jenkins > System, or clear the "
                        + "stores");
            }
            return PlaintextClientSecurity.PROVIDER;
        }
        // Absent means the JVM's own trust store, which is what TrustManagerFactory does with a null
        // KeyStore. Right for a publicly signed node, wrong for the private CA most clusters use --
        // so it is a fallback and not a default worth recommending.
        final KeyStore trust = tlsTruststore == null
                ? null
                : pkcs12(tlsTruststore, tlsTruststorePassword, "trust store");
        if (tlsKeystore == null) {
            // Server-authenticated TLS: this controller checks the node and presents nothing of its
            // own. Enough under `--client-auth token`, never enough under `--client-auth mtls`.
            return TlsClientSecurityProvider.serverAuthOnly(TlsConfig.of(sslContext(null, trust)));
        }
        return new TlsClientSecurityProvider(
                TlsConfig.of(sslContext(pkcs12(tlsKeystore, tlsKeystorePassword, "key store"), trust)));
    }

    // discas wraps every failure here in a bare RuntimeException, so there is no narrower type to
    // catch. What it is worth catching for is the sentence: a key that the store's password does not
    // unlock is an operator error, and it should read as one rather than as a stack trace in a build
    // that was only trying to find out whether it may run.
    private SSLContext sslContext(final KeyStore key, final KeyStore trust)
            throws AbortException {
        try {
            return key == null
                    ? TlsContexts.buildTrustOnly(trust)
                    : TlsContexts.build(key, password(tlsKeystorePassword), trust);
        } catch (final RuntimeException notUsable) {
            throw new AbortException("piplex: could not set up TLS from the configured stores: "
                    + rootCause(notUsable));
        }
    }

    // Read on every build of the store rather than held open, so that a rotated certificate is picked
    // up by saving the configuration -- which closes the store anyway -- instead of by a restart.
    private static KeyStore pkcs12(final String file, final Secret secret, final String what)
            throws AbortException {
        final Path path;
        try {
            path = Path.of(file);
        } catch (final InvalidPathException notAPath) {
            throw new AbortException("piplex: the TLS " + what + " path '" + file + "' is not a path");
        }
        try (InputStream bytes = Files.newInputStream(path)) {
            final KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(bytes, password(secret));
            return store;
        } catch (final IOException | GeneralSecurityException notReadable) {
            // The path is in the message because the commonest two failures are a file the controller
            // cannot read and a password that does not open it, and one of those is answered by
            // looking at the file.
            throw new AbortException("piplex: could not read the PKCS12 TLS " + what + " '" + file
                    + "': " + rootCause(notReadable));
        }
    }

    // A blank password is a store that has none, which trust stores usually are. null rather than an
    // empty array, because that is what KeyStore.load reads as "do not check the integrity of this
    // file" -- and an empty array means a password that happens to be empty, which is a different
    // thing and fails on a store that has none.
    private static char[] password(final Secret secret) {
        return secret == null ? null : secret.getPlainText().toCharArray();
    }

    private static String plainText(final Secret secret) {
        return secret == null ? null : secret.getPlainText();
    }

    // The outermost message is discas' or the JDK's own wrapper -- "Failed to build mTLS SSLContext"
    // -- and the sentence worth printing is underneath it.
    private static String rootCause(final Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        final String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }

    // What a person looking at the cluster's connections sees next to this controller's client id. The
    // id alone is only claimed -- in the default AllowAll mode nothing checks it -- so it answers "who
    // says it is connecting" and not "what is this". This answers the second, and it is the only
    // reason to send it: an operator finding an unexpected connection should not have to guess that
    // some string is a Jenkins controller running piplex.
    //
    // Package-private: what it produces goes out at CLIENT_HELLO and is never read back here, so the
    // bound it has to respect is otherwise checkable only by connecting.
    ClientDescription description() {
        final String owner = ownerId == null || ownerId.isEmpty() ? null : ownerId;
        return ClientDescription.of(truncate(owner == null
                ? "piplex Jenkins plugin"
                : "piplex Jenkins plugin, controller " + owner));
    }

    // ownerId is typed in by hand and discas refuses a description over its limit, so a long one is
    // shortened rather than allowed to fail the build of the store. The text is for a person to read;
    // losing its tail costs nothing, and not connecting would cost the run.
    private static String truncate(final String text) {
        String shortened = text;
        while (KvLimits.utf8Length(shortened) > KvLimits.MAX_CLIENT_DESCRIPTION_BYTES) {
            shortened = shortened.substring(0, shortened.length() - 1);
        }
        // Cutting by one char at a time can stop between the halves of a surrogate pair, and half a
        // character is not one.
        if (!shortened.isEmpty() && Character.isHighSurrogate(shortened.charAt(shortened.length() - 1))) {
            shortened = shortened.substring(0, shortened.length() - 1);
        }
        return shortened;
    }

    private synchronized void changed() {
        final CoordinationStore previous = store;
        final ScheduledExecutorService previousScheduler = scheduler;
        store = null;
        time = null;
        scheduler = null;
        if (previous != null) {
            previous.close();
        }
        if (previousScheduler != null) {
            previousScheduler.shutdownNow();
        }
        save();
    }

    // Package-private for the same reason as description(): what it parsed is otherwise observable
    // only by watching where a client dials.
    Map<NodeId, InetSocketAddress> cluster() throws AbortException {
        final Map<NodeId, InetSocketAddress> cluster = new LinkedHashMap<>();
        if (nodes == null) {
            return cluster;
        }
        for (final String entry : nodes.split("[,\\s]+")) {
            if (entry.isBlank()) {
                continue;
            }
            final String[] parts = entry.split("[=:]");
            if (parts.length != 3) {
                throw new AbortException("piplex: expected nodeId=host:port, got '" + entry + "'");
            }
            cluster.put(NodeId.of(parts[0]), new InetSocketAddress(parts[1], port(parts[2], entry)));
        }
        return cluster;
    }

    private static int port(final String text, final String entry) throws AbortException {
        try {
            return Integer.parseInt(text);
        } catch (final NumberFormatException notANumber) {
            // Typed into a form by a person, so it is worth the same sentence the rest of the entry
            // gets rather than a NumberFormatException from somewhere down the stack.
            throw new AbortException("piplex: expected nodeId=host:port, got '" + entry + "'");
        }
    }

    private static String trimmed(final String value) {
        if (value == null) {
            return null;
        }
        final String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    // A form posts an empty password as an empty Secret rather than as nothing at all, and an empty
    // token sent to a cluster is a failed handshake rather than the "no token configured" the
    // operator who cleared the field meant.
    private static Secret secret(final Secret value) {
        if (value == null) {
            return null;
        }
        return value.getPlainText().isEmpty() ? null : value;
    }
}
