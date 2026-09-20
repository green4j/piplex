/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import com.cloudbees.plugins.credentials.common.StandardCertificateCredentials;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.impl.CertificateCredentialsImpl;
import hudson.AbortException;
import hudson.BulkChange;
import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.model.Descriptor.FormException;
import hudson.init.Initializer;
import hudson.init.Terminator;
import hudson.model.TaskListener;
import hudson.model.listeners.SaveableListener;
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
import io.github.green4j.piplex.Environment;
import io.github.green4j.piplex.Piplex;
import io.github.green4j.piplex.TimeSource;
import io.github.green4j.piplex.discas.DiscasCoordinationStore;
import io.github.green4j.piplex.observe.TextPiplexObserver;
import io.github.green4j.piplex.store.CoordinationStore;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionList;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest2;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * The Jenkins composition root for controller identity, discas connectivity and piplex primitives.
 *
 * <p>{@code ownerId} is the stable controller identity. The authenticated connection is either a
 * token over TLS with no client key store, or mTLS with a client key store and no token. Node identity
 * verification is enabled by default and checks membership in the configured cluster.
 *
 * <p>Builds see one saved settings snapshot. Saving closes the previous store, so runs using it are
 * eventually revoked; the scheduler remains alive until Jenkins stops so those failures are observed.
 * Background watch cost is controlled by {@code watchPollPeriod}; admitted-run watches always use
 * linearizable reads at the client period.
 */
@Extension
@Symbol("piplex")
public final class PiplexConfiguration extends GlobalConfiguration {

    private String ownerId;
    private String clientId;
    private String nodes;
    // Which set of orchestrations this controller's work belongs to unless a step names another.
    private String environment;

    private Secret token;
    private boolean tls;
    private String tlsKeystore;
    private Secret tlsKeystorePassword;
    private String tlsTruststore;
    private Secret tlsTruststorePassword;
    // Initialised on, and read back from disk only where it was written: a controller configured before
    // this existed has no entry for it, and what it gets is the check rather than the absence of one.
    private boolean tlsVerifyNodeIdentity = true;
    private String watchPollPeriod;

    private static volatile CoordinationStore supplied;
    private static volatile TimeSource suppliedTime;

    private transient ScheduledExecutorService scheduler;
    private transient TimeSource time;
    private transient CoordinationStore store;
    private transient volatile boolean binding;
    // What builds read: the settings as last saved, published whole by save().
    private transient volatile Settings applied;
    // Guarded by this: the mark this process writes under its owner id, and the timer that writes it.
    private transient OwnerHeartbeat heartbeat;
    private transient TimeSource.Cancellable beat;
    private transient boolean stopped;

    public PiplexConfiguration() {
        load();
        applied = snapshot();
    }

    /**
     * @return the singleton Jenkins keeps
     */
    public static PiplexConfiguration get() {
        return GlobalConfiguration.all().get(PiplexConfiguration.class);
    }

    /**
     * @return the singleton Jenkins keeps
     * @throws AbortException if Jenkins has none to give
     */
    static PiplexConfiguration require() throws AbortException {
        final PiplexConfiguration configuration = get();
        if (configuration == null) {
            throw new AbortException("piplex: the plugin's configuration is not available in this "
                    + "Jenkins, so the controller's owner id and store cannot be read");
        }
        return configuration;
    }

    /**
     * @return this controller's identity as last entered, which a build uses only once it is saved
     */
    public synchronized String getOwnerId() {
        return ownerId;
    }

    /**
     * @param value this controller's identity
     */
    @DataBoundSetter
    public synchronized void setOwnerId(final String value) {
        this.ownerId = trimmed(value);
        changed();
    }

    /**
     * @return the identity this controller connects to discas under
     */
    public synchronized String getClientId() {
        return clientId;
    }

    /**
     * @param value the identity to connect under; blank means the same as {@code ownerId}
     */
    @DataBoundSetter
    public synchronized void setClientId(final String value) {
        this.clientId = trimmed(value);
        changed();
    }

    /**
     * @return the default environment as last entered, or {@code null} when none was
     */
    public synchronized String getEnvironment() {
        return environment;
    }

    /**
     * @param value which set of orchestrations this controller's work belongs to; blank means
     *              {@link Environment#DEFAULT}
     */
    @DataBoundSetter
    public synchronized void setEnvironment(final String value) {
        this.environment = trimmed(value);
        changed();
    }

    /**
     * @return the cluster, as {@code nodeId=host:port} entries separated by commas or newlines
     */
    public synchronized String getNodes() {
        return nodes;
    }

    /**
     * @param value the cluster
     */
    @DataBoundSetter
    public synchronized void setNodes(final String value) {
        this.nodes = trimmed(value);
        changed();
    }

    /**
     * @return the shared token this controller authenticates with, or {@code null} for none
     */
    public synchronized Secret getToken() {
        return token;
    }

    /**
     * @param value the token a cluster running {@code --client-auth token} expects; blank means none
     */
    @DataBoundSetter
    public synchronized void setToken(final Secret value) {
        this.token = secret(value);
        changed();
    }

    /**
     * @return whether the connection to the cluster is TLS
     */
    public synchronized boolean isTls() {
        return tls;
    }

    /**
     * @param value whether to connect over TLS. Required by a cluster running
     *              {@code --client-tls} or {@code --client-auth mtls}
     */
    @DataBoundSetter
    public synchronized void setTls(final boolean value) {
        this.tls = value;
        changed();
    }

    /**
     * @return the PKCS12 file holding this controller's client certificate, or {@code null} for none
     */
    public synchronized String getTlsKeystore() {
        return tlsKeystore;
    }

    /**
     * @param value a PKCS12 file on this controller holding its client certificate and key. Required
     *              by a cluster running {@code --client-auth mtls}; blank means this controller
     *              presents no certificate and only the node is authenticated
     */
    @DataBoundSetter
    public synchronized void setTlsKeystore(final String value) {
        this.tlsKeystore = trimmed(value);
        changed();
    }

    /**
     * @return the password of {@link #getTlsKeystore()}, or {@code null} if it needs none
     */
    public synchronized Secret getTlsKeystorePassword() {
        return tlsKeystorePassword;
    }

    /**
     * @param value the key store's password, which is also taken as the private key's
     */
    @DataBoundSetter
    public synchronized void setTlsKeystorePassword(final Secret value) {
        this.tlsKeystorePassword = secret(value);
        changed();
    }

    /**
     * @return the PKCS12 file of CAs the nodes are checked against, or {@code null} for the JVM's own
     */
    public synchronized String getTlsTruststore() {
        return tlsTruststore;
    }

    /**
     * @param value a PKCS12 file holding the CA that signed the nodes' client-port certificates.
     *              Blank falls back to the JVM's default trust store, which is right only where a
     *              public CA signed them
     */
    @DataBoundSetter
    public synchronized void setTlsTruststore(final String value) {
        this.tlsTruststore = trimmed(value);
        changed();
    }

    /**
     * @return the password of {@link #getTlsTruststore()}, or {@code null} if it needs none
     */
    public synchronized Secret getTlsTruststorePassword() {
        return tlsTruststorePassword;
    }

    /**
     * @param value the trust store's password; most hold public certificates and have none
     */
    @DataBoundSetter
    public synchronized void setTlsTruststorePassword(final Secret value) {
        this.tlsTruststorePassword = secret(value);
        changed();
    }

    /**
     * @return whether a node's certificate has to name a node this controller was configured with
     */
    public synchronized boolean isTlsVerifyNodeIdentity() {
        return tlsVerifyNodeIdentity;
    }

    /**
     * @param value whether to require the certificate a node presents to name one of the configured
     *              nodes, by node id or by host. Turning it off leaves the certificate chain as the
     *              only test, which is why it may only be turned off where a trust store pins exactly
     *              which certificates this controller trusts
     */
    @DataBoundSetter
    public synchronized void setTlsVerifyNodeIdentity(final boolean value) {
        this.tlsVerifyNodeIdentity = value;
        changed();
    }

    /**
     * @return the shortest gap between polls of a waiting run's watch, or {@code null} for the
     *         client's own
     */
    public synchronized String getWatchPollPeriod() {
        return watchPollPeriod;
    }

    /**
     * @param value the shortest gap to leave after one poll of a waiting run's watch answered before
     *              making the next, as {@code 30s}, {@code 2m} or ISO-8601; the gap actually taken is spread up
     *              to five times it. Blank leaves it to the discas client, whose own period is one
     *              second. At least {@code 500ms}
     */
    @DataBoundSetter
    public synchronized void setWatchPollPeriod(final String value) {
        this.watchPollPeriod = trimmed(value);
        changed();
    }

    /**
     * One Save is one change, whatever it touched.
     *
     * <p>The form is bound field by field. {@code binding} stops each setter from saving on its own,
     * and the one {@link #save()} at the end publishes the whole form to builds at once.
     */
    @Override
    public synchronized boolean configure(final StaplerRequest2 request, final JSONObject json)
            throws FormException {
        // A form that fails half way through binding is put back as it was: the store in use was built
        // from the old values, and left half bound the fields would no longer be what it was built from.
        final Settings before = snapshot();
        binding = true;
        try {
            request.bindJSON(this, json);
        } catch (final RuntimeException notBound) {
            restore(before);
            throw notBound;
        } finally {
            binding = false;
        }
        try {
            // Here and not in check(): a form is a whole set of settings at once, which is what makes
            // a combination judgeable, while every setter reaches check() through save() and one at a
            // time is where an invalid combination on the way to a valid one is legitimate. Left to
            // the first build, an unticked TLS box with a store behind it saves green and then fails
            // every job on the controller, naming fields the closed block hides.
            combinations(snapshot(), ClientCredentials.of(snapshot()));
        } catch (final AbortException refused) {
            restore(before);
            throw new FormException(refused.getMessage(), refused, "tls");
        }
        try {
            changed();
        } catch (final InvalidSetting refused) {
            // Shown beside the field on the page rather than as a stack trace.
            throw new FormException(refused.getMessage(), refused, refused.field());
        }
        return true;
    }

    /**
     * Publishes the settings to builds and closes the store built from the previous ones.
     *
     * <p>Inside a {@link BulkChange} -- which is how Configuration as Code applies its setters -- this
     * waits for the commit, so a reload is one change as well.
     *
     * <p>Fails closed. Nothing is published until the settings are on disk: {@code Descriptor.save()}
     * only logs a failed write, which would leave builds on an owner id the controller forgets on
     * restart. A failed write puts the fields back as last saved and throws.
     *
     * <p>Settings which could never build a store are refused before that: an owner id with a
     * {@code /}, a node list that does not parse, a poll period below the floor. Refused, the fields are
     * put back as last saved and the store in use stays open, so runs in flight are not revoked for a
     * typo. Empty fields are not refused, and neither is a combination of TLS settings: setters called
     * one at a time pass through such states on the way to a valid one.
     *
     * <p>The scheduler and the time source it backs are deliberately left alone. A run in flight is
     * holding both, and what has to happen to it is that its next renewal reaches a closed store and
     * fails -- which is how it is revoked once its grace period is out. Shut the scheduler down and
     * there is no next renewal: nothing fails, nothing is revoked, and the run carries on holding work
     * whose lease another controller is already free to take.
     *
     * @throws IllegalArgumentException if a setting could never build a store
     * @throws UncheckedIOException      if the settings could not be written
     */
    @Override
    public synchronized void save() {
        if (BulkChange.contains(this)) {
            return;
        }
        final Settings next = snapshot();
        try {
            check(next);
        } catch (final InvalidSetting refused) {
            restore(applied);
            throw refused;
        }
        try {
            getConfigFile().write(this);
        } catch (final IOException failed) {
            restore(applied);
            throw new UncheckedIOException(
                    "Piplex settings could not be saved, the previously saved ones stay in force", failed);
        }
        applied = next;
        final CoordinationStore previous = store;
        store = null;
        if (previous != null) {
            previous.close();
        }
        SaveableListener.fireOnChange(this, getConfigFile());
    }

    private Settings snapshot() {
        return new Settings(ownerId, clientId, nodes, environment, token, tls, tlsKeystore,
                tlsKeystorePassword, tlsTruststore, tlsTruststorePassword, tlsVerifyNodeIdentity,
                watchPollPeriod);
    }

    private void restore(final Settings settings) {
        ownerId = settings.ownerId();
        clientId = settings.clientId();
        nodes = settings.nodes();
        environment = settings.environment();
        token = settings.token();
        tls = settings.tls();
        tlsKeystore = settings.tlsKeystore();
        tlsKeystorePassword = settings.tlsKeystorePassword();
        tlsTruststore = settings.tlsTruststore();
        tlsTruststorePassword = settings.tlsTruststorePassword();
        tlsVerifyNodeIdentity = settings.tlsVerifyNodeIdentity();
        watchPollPeriod = settings.watchPollPeriod();
    }

    private static void check(final Settings settings) {
        try {
            if (settings.ownerId() != null) {
                readable(settings.ownerId());
            }
        } catch (final AbortException refused) {
            throw new InvalidSetting("ownerId", refused);
        }
        try {
            cluster(settings);
        } catch (final AbortException refused) {
            throw new InvalidSetting("nodes", refused);
        }
        try {
            environment(settings);
        } catch (final AbortException refused) {
            throw new InvalidSetting("environment", refused);
        }
        try {
            watchPollPeriod(settings);
        } catch (final AbortException refused) {
            throw new InvalidSetting("watchPollPeriod", refused);
        }
    }

    /**
     * A setting {@link #save()} refused, and which field it was in.
     */
    static final class InvalidSetting extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        private final String field;

        InvalidSetting(final String field, final AbortException refused) {
            super(refused.getMessage(), refused);
            this.field = field;
        }

        String field() {
            return field;
        }
    }

    /**
     * Every setting at one moment, to put back when a form cannot be bound or saved.
     */
    private record Settings(String ownerId,
                            String clientId,
                            String nodes,
                            String environment,
                            Secret token,
                            boolean tls,
                            String tlsKeystore,
                            Secret tlsKeystorePassword,
                            String tlsTruststore,
                            Secret tlsTruststorePassword,
                            boolean tlsVerifyNodeIdentity,
                            String watchPollPeriod) {
    }

    /**
     * What a client authenticates with: this controller's own configuration, or an operator identity
     * standing in for it.
     *
     * <p>Separated from {@link Settings} because the transport around it never varies. An operator
     * block may connect as somebody else, but it connects to the same cluster, over the same TLS, and
     * checks the nodes against the same trust store -- so only the two fields a discas node
     * authenticates on are substitutable, and every rule about how they may be combined is applied to
     * whichever pair is in force.
     *
     * <p>A key store arrives already loaded from a credential, or as a path still to be read. The
     * distinction is not laziness for its own sake: a path is read only after the combination has been
     * accepted, so a store configured while TLS is off is reported as that rather than as a file that
     * would not open.
     */
    private record ClientCredentials(Secret token,
                                     String keyStoreFile,
                                     Secret keyStorePassword,
                                     KeyStore loadedKeyStore) {

        static ClientCredentials of(final Settings settings) {
            return new ClientCredentials(settings.token(), settings.tlsKeystore(),
                    settings.tlsKeystorePassword(), null);
        }

        boolean hasKeyStore() {
            return keyStoreFile != null || loadedKeyStore != null;
        }

        KeyStore keyStore() throws AbortException {
            return loadedKeyStore != null
                    ? loadedKeyStore
                    : pkcs12(keyStoreFile, keyStorePassword, "key store");
        }
    }

    @Override
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
     * @return the primitives, in this controller's default environment
     * @throws AbortException if the controller has not been configured
     */
    public synchronized Piplex piplexFor(final TaskListener listener) throws AbortException {
        return piplexFor(listener, null);
    }

    /**
     * The primitives, as this run should see them, in the environment it asked for.
     *
     * @param listener the run's log
     * @param named    the environment the step named, or {@code null} to use the controller's default
     * @return the primitives
     * @throws AbortException if the controller has not been configured, or the name cannot be one
     */
    public synchronized Piplex piplexFor(final TaskListener listener, final String named)
            throws AbortException {
        return new Piplex(store(), time(),
                new TextPiplexObserver(line -> listener.getLogger().println("piplex: " + line)),
                environmentOf(named));
    }

    /**
     * The environment a step's own value names, or this controller's default where it names none.
     *
     * @param named what the step asked for, may be {@code null} or blank
     * @return the environment to work in
     * @throws AbortException if what the step named cannot be an environment
     */
    public synchronized Environment environmentOf(final String named) throws AbortException {
        if (named == null || named.isBlank()) {
            return environment(applied);
        }
        try {
            return Environment.of(named.trim());
        } catch (final IllegalArgumentException refused) {
            throw new AbortException("piplex: " + refused.getMessage()
                    + ". It is the environment named by this step");
        }
    }

    /**
     * @return the environment this controller's work belongs to unless a step names another
     * @throws AbortException if what was saved cannot name one
     */
    public synchronized Environment defaultEnvironment() throws AbortException {
        return environment(applied);
    }

    /**
     * The owner id and the primitives, from one reading of the settings.
     *
     * <p>Asked for separately they are two acquisitions of this monitor, and a Save landing between them
     * gives a run the old identity and a store built from the new form. {@link #configure} goes to
     * trouble to make one Save one change; this is the same thing from the reader's side.
     *
     * @param listener the run's log
     * @return both, as one settled answer
     * @throws AbortException if the controller has not been configured
     */
    public synchronized Configured configuredFor(final TaskListener listener) throws AbortException {
        return configuredFor(listener, null);
    }

    /**
     * The owner id and the primitives of one environment, from one reading of the settings.
     *
     * @param listener the run's log
     * @param named    the environment the step named, or {@code null} for this controller's default
     * @return both, as one settled answer
     * @throws AbortException if the controller has not been configured, or the name cannot be one
     */
    public synchronized Configured configuredFor(final TaskListener listener, final String named)
            throws AbortException {
        final Configured configured =
                new Configured(requireOwnerId(), piplexFor(listener, named), time());
        startHeartbeat();
        return configured;
    }

    /**
     * Starts writing this process's mark under its owner id, once Jenkins is up.
     */
    @Initializer(after = InitMilestone.JOB_CONFIG_ADAPTED)
    public static void startHeartbeatOnStartup() {
        final PiplexConfiguration configuration = get();
        if (configuration != null) {
            configuration.startHeartbeat();
        }
    }

    /**
     * Lets go of everything this controller holds, once its builds are suspended.
     *
     * <p>For a JVM that outlives Jenkins -- a servlet container, a test restarting it. Its daemon
     * threads would otherwise renew the stopped controller's leases for as long as the JVM runs, and
     * no other controller could take over. The steps go first, so closing the store revokes nothing.
     */
    @Terminator(requires = FlowExecutionList.EXECUTIONS_SUSPENDED)
    public static void stopping() {
        ExclusiveStepExecution.abandonAll();
        OperatorStepExecution.closeAll();
        final PiplexConfiguration configuration = get();
        if (configuration != null) {
            configuration.stop();
        }
    }

    synchronized void stop() {
        stopped = true;
        if (beat != null) {
            beat.cancel();
            beat = null;
        }
        final CoordinationStore previous = store;
        store = null;
        if (previous != null) {
            previous.close();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
            time = null;
        }
    }

    /**
     * @return the owner id another live controller was seen using lately, or {@code null}
     */
    public synchronized String duplicatedOwner() {
        return heartbeat == null ? null : heartbeat.duplicated();
    }

    /**
     * @return the owner id whose heartbeat has not been written lately, or {@code null}
     */
    public synchronized String silentOwner() {
        return heartbeat == null ? null : heartbeat.silent();
    }

    // Idempotent, and called again by every build: a timer the scheduler refused is armed again then.
    synchronized void startHeartbeat() {
        if (beat != null || stopped) {
            return;
        }
        final TimeSource beatTime = suppliedTime != null ? suppliedTime : schedulerTime();
        if (heartbeat == null) {
            heartbeat = new OwnerHeartbeat(beatTime);
        }
        try {
            beat = beatTime.schedule(OwnerHeartbeat.EVERY, this::beatThenArm);
        } catch (final RuntimeException refused) {
            beat = null;
        }
    }

    private synchronized TimeSource schedulerTime() {
        scheduler();
        return time;
    }

    private void beatThenArm() {
        synchronized (this) {
            beat = null;
        }
        // Outside the monitor, and a configuration Jenkins no longer uses stops here: a reloaded one
        // runs its own.
        if (Jenkins.getInstanceOrNull() == null || get() != this) {
            return;
        }
        beatNow().whenComplete((ignored, never) -> startHeartbeat());
    }

    /**
     * One beat, now.
     *
     * @return completes once it is over; never exceptionally
     */
    CompletionStage<Void> beatNow() {
        final String owner;
        final CoordinationStore beatStore;
        final OwnerHeartbeat beating;
        // The controller's own, never a step's: the mark says this process is using this owner id, and
        // a controller has exactly one identity however many environments it runs work in.
        final Environment in;
        synchronized (this) {
            owner = applied.ownerId();
            beating = heartbeat;
            CoordinationStore found = null;
            Environment saved = null;
            if (owner != null && owner.indexOf('/') < 0 && beating != null) {
                try {
                    found = store();
                    saved = defaultEnvironment();
                } catch (final AbortException unconfigured) {
                    found = null;
                }
            }
            beatStore = found;
            in = saved;
        }
        if (beatStore == null || in == null) {
            return CompletableFuture.completedFuture(null);
        }
        return beating.tick(beatStore, in, owner);
    }

    /**
     * One reading of the settings a run acts on.
     *
     * @param ownerId this controller's identity
     * @param piplex  the primitives, over the store those settings name
     * @param time    where their time comes from
     */
    public record Configured(String ownerId, Piplex piplex, TimeSource time) {
    }

    /**
     * @return this controller's identity
     * @throws AbortException if it has not been set, or cannot be told apart from the run beside it
     */
    public synchronized String requireOwnerId() throws AbortException {
        final String ownerId = applied.ownerId();
        if (ownerId == null) {
            throw new AbortException(
                    "piplex is not configured: set the controller's owner id in Manage Jenkins > System");
        }
        // save() refuses it too, but a file written before it did is read back as it is.
        readable(ownerId);
        return ownerId;
    }

    private static void readable(final String ownerId) throws AbortException {
        if (ownerId.indexOf('/') >= 0) {
            // The core escapes each part before joining them, so a slash here can no longer make two
            // holders share one identity. It is still refused at the door, because this string is also
            // what a designation names and what every log line and every record carries, and one that
            // reads as a path is an operator error worth catching where it is asked for -- once, on the
            // settings page -- rather than leaving in a night's worth of keys.
            throw new AbortException(
                    "piplex: the owner id must not contain '/'. It names this controller in the "
                            + "designation, in the lease and in every log line, and a '/' reads as "
                            + "structure in all three. Current value: '" + ownerId + "'");
        }
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

    synchronized CoordinationStore store() throws AbortException {
        if (supplied != null) {
            return supplied;
        }
        if (store == null) {
            build();
        }
        return store;
    }

    /**
     * The clock every wait in this plugin counts on, monotonic and shared with the store's own timers.
     *
     * <p>Package-private alongside {@link #store()}, and for a related reason: a step that waits for
     * something on this controller has to count on the same clock the runs it is waiting for do.
     *
     * @return the clock
     * @throws AbortException if the controller has not been configured
     */
    synchronized TimeSource time() throws AbortException {
        if (suppliedTime != null) {
            return suppliedTime;
        }
        if (store == null) {
            build();
        }
        return time;
    }

    /**
     * The primitives of one operator block, and the client they hold open.
     *
     * <p>Closed when the block ends. Acting as this controller it owns nothing and closing it is a
     * no-op, because the store it borrowed outlives the block.
     */
    static final class OperatorSession implements AutoCloseable {

        private final Piplex piplex;
        private final CoordinationStore own;

        private OperatorSession(final Piplex piplex, final CoordinationStore own) {
            this.piplex = piplex;
            this.own = own;
        }

        Piplex piplex() {
            return piplex;
        }

        @Override
        public void close() {
            if (own != null) {
                own.close();
            }
        }
    }

    /**
     * The primitives an operator block writes through, under an identity of its own where it named
     * one.
     *
     * <p>Never cached. A cached client would be handed to the next build that named the same
     * credential -- a build whose right to use it was never checked -- and that is exactly the
     * accidental widening this whole arrangement exists to prevent. Building it every time costs a
     * handshake, and operator writes are rare enough to pay it.
     *
     * <p>Only the two fields a discas node authenticates on come from the credential. The cluster,
     * TLS, the trust store and the poll period stay this controller's, so a block cannot quietly
     * talk to somewhere else.
     *
     * @param listener   the run's log
     * @param named      the environment the block named, or {@code null} for this controller's default
     * @param clientId   the identity to connect as, or {@code null} to act as this controller
     * @param credential what to authenticate with, or {@code null} to act as this controller
     * @return the session, which the caller closes
     * @throws AbortException if the controller has not been configured, or the combination is refused
     */
    synchronized OperatorSession operatorSessionFor(final TaskListener listener,
                                                    final String named,
                                                    final String clientId,
                                                    final StandardCredentials credential)
            throws AbortException {
        if (credential == null) {
            return new OperatorSession(piplexFor(listener, named), null);
        }
        final Settings settings = applied;
        if (!settings.tls()) {
            // A discas node in allowall takes a client id as a claim rather than as a proof, so a
            // second identity there separates nothing: anything that can reach the port can say it is
            // piplex-ops. Refused rather than allowed to look like separation.
            throw new AbortException("piplex: this block asks to act as '" + clientId + "', but this "
                    + "controller connects without TLS, where a discas node takes a client id as a "
                    + "claim rather than as a proof. A separate operator identity means something only "
                    + "over token or mTLS");
        }
        // Everything refusable first, for the reason build() gives: a client made before them is one
        // nobody holds and nobody closes, and this one carries its own event loop and connections.
        final Environment in = environmentOf(named);
        final Duration polling = watchPollPeriod(settings);
        final TimeSource clock = time();
        final ClientCredentials as = credentialsOf(credential, clientId);
        final CoordinationStore own = new DiscasCoordinationStore(
                client(settings, ClientId.of(clientId), as),
                ReadConsistency.LINEARIZABLE, polling, true);
        return new OperatorSession(
                new Piplex(own, clock,
                        new TextPiplexObserver(line -> listener.getLogger().println("piplex: " + line)),
                        in),
                own);
    }

    /**
     * What a Jenkins credential authenticates a discas client with.
     *
     * @param credential the credential the block named, already resolved against the running build
     * @param clientId   the identity it is being used for, for the sentence a refusal reads as
     * @return the pair a discas node admits a client on
     * @throws AbortException if the credential is of a kind a discas node does not admit clients on
     */
    private static ClientCredentials credentialsOf(final StandardCredentials credential,
                                                   final String clientId) throws AbortException {
        if (credential instanceof StandardCertificateCredentials certificate) {
            final KeyStore store = certificate.getKeyStore();
            checkNamed(store, clientId);
            // The key password is not on the interface, only on the type the Jenkins UI creates. A
            // store from anywhere else is tried without one, which is right for a store that has none
            // and reports itself clearly when it has.
            final Secret password = credential instanceof CertificateCredentialsImpl created
                    ? created.getPassword()
                    : null;
            return new ClientCredentials(null, null, password, store);
        }
        if (credential instanceof StringCredentials token) {
            return new ClientCredentials(token.getSecret(), null, null, null);
        }
        throw new AbortException("piplex: the credential for '" + clientId + "' is a "
                + credential.getClass().getSimpleName() + ", and a discas node admits a client on a "
                + "client certificate or a token. Use a Certificate credential for a cluster running "
                + "--client-auth mtls, or a Secret text credential for one running --client-auth token");
    }

    /**
     * Checks that the certificate a block will present is the identity the block claims.
     *
     * <p>Under mTLS the node authenticates a client on its certificate's common name, and the ACL is
     * written against that name. {@code clientId} is a separate string, and nothing has ever made the
     * two agree -- so a block naming one identity while presenting another is written down as the
     * first and granted the rights of the second. Whichever of the two is wrong, acting is not the
     * answer: the grant being claimed is not the grant that will be enforced.
     *
     * <p>Refused here rather than left to the node, because the node's refusal arrives as a denied
     * key at whatever moment the job first writes one, and says nothing about why.
     *
     * @param store    the key store the credential carries
     * @param clientId the identity the block names
     * @throws AbortException if the store cannot be read, holds no certificate, or names somebody else
     */
    private static void checkNamed(final KeyStore store, final String clientId) throws AbortException {
        final String common;
        try {
            common = commonNameOf(store);
        } catch (final GeneralSecurityException unreadable) {
            throw new AbortException("piplex: the certificate credential for '" + clientId + "' cannot "
                    + "be read (" + unreadable.getMessage() + "), so the identity it would present "
                    + "cannot be checked against that name");
        }
        if (common == null) {
            throw new AbortException("piplex: the certificate credential for '" + clientId + "' has no "
                    + "common name, and a discas node running --client-auth mtls admits a client on "
                    + "exactly that. Issue the certificate with CN=" + clientId);
        }
        if (!common.equals(clientId)) {
            throw new AbortException("piplex: this block acts as '" + clientId + "', but its credential "
                    + "presents a certificate for '" + common + "'. A discas node grants the rights of "
                    + "the certificate and this build would record the other name. Name the identity "
                    + "the certificate carries, or use the credential issued to '" + clientId + "'");
        }
    }

    /**
     * @param store the key store the credential carries
     * @return the common name of the certificate it would present, or {@code null} when it holds none
     * @throws GeneralSecurityException if the store cannot be read
     */
    private static String commonNameOf(final KeyStore store) throws GeneralSecurityException {
        final Enumeration<String> aliases = store.aliases();
        while (aliases.hasMoreElements()) {
            final String alias = aliases.nextElement();
            // The key entry and no other: a store may also carry the CA it was signed by, and that
            // certificate names the issuer rather than this client.
            if (store.isKeyEntry(alias) && store.getCertificate(alias) instanceof X509Certificate leaf) {
                return NodeIdentity.commonNameIn(leaf);
            }
        }
        return null;
    }

    /**
     * One client, whoever it connects as.
     *
     * <p>Both the controller's store and an operator block's are built through here, so the cluster a
     * client reaches, the rules about how credentials may be combined, and the sentence a refusal
     * reads as are the same for either. What varies between them is the identity and the two fields
     * it authenticates with, and nothing else.
     *
     * @param settings    the configuration in force
     * @param identity    who to connect as
     * @param credentials what to authenticate with
     * @return the client, which whoever wraps it owns and closes
     * @throws AbortException if no nodes are configured, or the combination is refused
     */
    private static DisCasClient client(final Settings settings, final ClientId identity,
                                       final ClientCredentials credentials) throws AbortException {
        final Map<NodeId, InetSocketAddress> cluster = cluster(settings);
        if (cluster.isEmpty()) {
            throw new AbortException(
                    "piplex is not configured: set the discas nodes in Manage Jenkins > System");
        }
        return DisCasClientFactory.create(
                identity,
                description(settings),
                new TcpClientBootstrap(cluster, ClientTransportConfig.defaults(),
                        plainText(credentials.token()), security(settings, credentials)),
                DisCasClientConfig.defaults());
    }

    private void build() throws AbortException {
        final Settings settings = applied;
        // Read before the client exists, because it can be refused: a client made first would be one
        // nobody holds a reference to and nobody closes.
        final Duration pollPeriod = watchPollPeriod(settings);
        final ClientId identity = ClientId.of(
                settings.clientId() == null ? requireOwnerId() : settings.clientId());
        final DisCasClient client = client(settings, identity, ClientCredentials.of(settings));
        scheduler();
        // Watches read linearizably because what they return is acted on rather than re-read: the two
        // an admitted run holds revoke it on the value the watch itself returned, so a stale one stops
        // the run late -- which is the overlap window this is here to keep narrow. It is not a setting
        // for that reason. What a nightly job wants instead is a longer poll period, which trades the
        // same latency for the same saving, but explicitly and without depending on how far behind a
        // node happens to be.
        store = new DiscasCoordinationStore(client, ReadConsistency.LINEARIZABLE, pollPeriod, true);
    }

    /**
     * The timers every run in flight is holding, started once and kept for the life of the controller.
     *
     * <p>Package-private for the same reason as {@link #cluster()}: that it survives a settings change
     * is what makes a run whose store has just been closed fail its next renewal and be revoked, and
     * the alternative -- no next renewal, no failure, no revocation -- is observable only by waiting out
     * a grace period that never comes.
     *
     * @return the scheduler
     */
    synchronized ScheduledExecutorService scheduler() {
        if (scheduler == null) {
            final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, runnable -> {
                final Thread thread = new Thread(runnable, "piplex");
                thread.setDaemon(true);
                return thread;
            });
            // Every renewal replaces a timer a lease long; left queued once cancelled, they pile up.
            executor.setRemoveOnCancelPolicy(true);
            scheduler = executor;
            time = TimeSource.of(scheduler);
        }
        return scheduler;
    }

    /**
     * The shortest gap between polls of a watch, or {@code null} to leave that to the discas client.
     *
     * <p>A watch in discas is a poll, not a subscription, and at one second -- the client's default --
     * an hour parked is thousands of consensus rounds per key to learn nothing. A job that runs once a
     * day does not need to hear about a designation within a second, so this is the setting that makes
     * the standing cost of parking match how often the answer actually changes.
     *
     * <p>What it buys back is bounded by something this cannot reach: a parked candidate looks again
     * every {@code renewEvery} anyway, and a round always ends with a poll, so a period longer than
     * that round removes the polls inside it and leaves the two at its ends.
     *
     * <p>What it costs applies only while a candidate is parked: the gap taken is spread up to five
     * times this, and the candidate reads all guards again at the end of each round. An admitted run
     * keeps the discas client's own watch period because its answers revoke live work and must not be
     * slowed by a background-poll setting.
     *
     * @return the period, or {@code null}
     * @throws AbortException if what was typed is not a duration, or is below discas' floor
     */
    Duration watchPollPeriod() throws AbortException {
        return watchPollPeriod(applied);
    }

    /**
     * The environment saved, or the default where the field was left empty.
     *
     * @param settings the settings
     * @return the environment every key this controller writes goes under
     * @throws AbortException if what was typed cannot name one
     */
    private static Environment environment(final Settings settings) throws AbortException {
        try {
            return Environment.orDefault(settings.environment());
        } catch (final IllegalArgumentException refused) {
            // Said with the field named, once, on the settings page: it is the leading segment of every
            // key this controller writes, and a night's worth of keys is the alternative place to find out.
            throw new AbortException("piplex: " + refused.getMessage()
                    + ". Set it in Manage Jenkins > System, or leave it empty for '"
                    + Environment.DEFAULT + "'");
        }
    }

    private static Duration watchPollPeriod(final Settings settings) throws AbortException {
        final String watchPollPeriod = settings.watchPollPeriod();
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
        return security(applied);
    }

    private static ClientSecurityProvider security(final Settings settings) throws AbortException {
        return security(settings, ClientCredentials.of(settings));
    }
    /**
     * The combinations of TLS settings no client can be built from. Apart from {@link #security} so
     * that a form can be refused on them, needing nothing off disk to judge.
     *
     * @param settings    what was filled in
     * @param credentials the secrets among it
     * @throws AbortException if no client can be built from the combination
     */

    private static void combinations(final Settings settings,
                                     final ClientCredentials credentials)
            throws AbortException {
        final Secret token = credentials.token();
        final boolean hasKeyStore = credentials.hasKeyStore();
        final String tlsTruststore = settings.tlsTruststore();
        if (!settings.tls()) {
            // Nothing is silently ignored: a store filled in with the box unticked is somebody who
            // believes this connection is encrypted, and it is not.
            if (hasKeyStore || tlsTruststore != null) {
                throw new AbortException("piplex: a TLS key store or trust store is configured but TLS "
                        + "is off. Tick 'Connect over TLS' in Manage Jenkins > System, or clear the "
                        + "stores");
            }
            if (token != null) {
                // Refused rather than sent, and there is no setting that allows it. The token is this
                // controller's password to the whole cluster, it goes out at CLIENT_HELLO on every
                // connection this controller ever makes, and anything on the path can read it once and
                // then be this controller for as long as the token lives. A cluster worth putting a
                // token on is a cluster worth putting TLS on.
                throw new AbortException("piplex: a token is configured but TLS is off, so it would "
                        + "cross the wire in clear on every connection. Tick 'Connect over TLS' in "
                        + "Manage Jenkins > System, or clear the token");
            }
            return;
        }
        if (token != null && hasKeyStore) {
            // A discas node authenticates clients one way at a time -- `--client-auth token` or
            // `--client-auth mtls` -- so filling in both means one of them is not what the cluster is
            // running, and which one it is cannot be worked out from here. Two profiles are safe, and
            // both of them are a whole answer on their own.
            throw new AbortException("piplex: both a token and a client certificate are configured, and "
                    + "a discas node admits clients one way at a time. Use the token with TLS and no "
                    + "key store, for a cluster running --client-auth token, or the key store and no "
                    + "token, for one running --client-auth mtls");
        }
        if (!settings.tlsVerifyNodeIdentity() && tlsTruststore == null) {
            // With the check off, a valid chain is the whole test -- and against the JVM's own trust
            // store that is no test at all: any host with a certificate from any CA the JVM trusts
            // answers as the cluster, and is handed the token in the same breath. A trust store makes
            // it a test again, because then it pins which certificates count.
            throw new AbortException("piplex: the node identity check is off and no trust store is "
                    + "configured, so any host with a certificate from any CA this JVM trusts would be "
                    + "accepted as a discas node. Tick 'Check the node's identity' in Manage Jenkins > "
                    + "System, or configure a trust store holding the nodes' own certificates. A CA in "
                    + "the trust store also works, but with the check off it accepts every certificate "
                    + "that CA ever issues as a node, which is not pinning");
        }
    }

    private static ClientSecurityProvider security(final Settings settings,
                                                   final ClientCredentials credentials)
            throws AbortException {
        combinations(settings, credentials);
        final boolean hasKeyStore = credentials.hasKeyStore();
        final String tlsTruststore = settings.tlsTruststore();
        if (!settings.tls()) {
            return PlaintextClientSecurity.PROVIDER;
        }
        // Absent means the JVM's own trust store, which is what TrustManagerFactory does with a null
        // KeyStore. Right for a publicly signed node, wrong for the private CA most clusters use --
        // so it is a fallback and not a default worth recommending.
        final KeyStore trust = tlsTruststore == null
                ? null
                : pkcs12(tlsTruststore, settings.tlsTruststorePassword(), "trust store");
        if (!hasKeyStore) {
            // Server-authenticated TLS: this controller checks the node and presents nothing of its
            // own. Enough under `--client-auth token`, never enough under `--client-auth mtls`.
            return TlsClientSecurityProvider.serverAuthOnly(
                    TlsConfig.of(sslContext(settings, null, null, trust)));
        }
        return new TlsClientSecurityProvider(TlsConfig.of(sslContext(settings,
                credentials.keyStore(), credentials.keyStorePassword(), trust)));
    }

    // discas wraps every failure here in a bare RuntimeException, so there is no narrower type to
    // catch. What it is worth catching for is the sentence: a key that the store's password does not
    // unlock is an operator error, and it should read as one rather than as a stack trace in a build
    // that was only trying to find out whether it may run.
    private static SSLContext sslContext(final Settings settings, final KeyStore key,
                                         final Secret keyPassword, final KeyStore trust)
            throws AbortException {
        try {
            if (!settings.tlsVerifyNodeIdentity()) {
                // Refused above unless a trust store is configured, so what is left here is trust
                // pinned to the certificates in it -- which is a test of identity of its own, if a
                // coarser one: the certificate is one of the few this controller was given.
                return key == null
                        ? TlsContexts.buildTrustOnly(trust)
                        : TlsContexts.build(key, password(keyPassword), trust);
            }
            return boundToTheNodes(settings, key, keyPassword, trust);
        } catch (final GeneralSecurityException | RuntimeException notUsable) {
            throw new AbortException("piplex: could not set up TLS from the configured stores: "
                    + rootCause(notUsable));
        }
    }

    /**
     * The same context the discas client would have built, with the identity of what answers checked
     * after its chain is.
     *
     * <p>Built here rather than by {@link TlsContexts} because the trust managers have to be wrapped
     * before the context is initialised with them, and that is the only difference: the protocol is
     * {@link TlsContexts#PROTOCOL}, the same one every other discas connection uses.
     *
     * @param settings    the settings being built from
     * @param key         the certificate to present, or {@code null} to present none
     * @param keyPassword what unlocks the key in it, or {@code null} where it has none
     * @param trust       what the nodes' certificates must chain to, or {@code null} for the JVM's own
     * @return the context
     * @throws GeneralSecurityException if the stores cannot be turned into managers
     * @throws AbortException           if no nodes are configured to check an identity against
     */
    private static SSLContext boundToTheNodes(final Settings settings, final KeyStore key,
                                              final Secret keyPassword, final KeyStore trust)
            throws GeneralSecurityException, AbortException {
        final TrustManagerFactory trusted =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trusted.init(trust);
        KeyManager[] presented = null;
        if (key != null) {
            final KeyManagerFactory keys =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(key, password(keyPassword));
            presented = keys.getKeyManagers();
        }
        final SSLContext context = SSLContext.getInstance(TlsContexts.PROTOCOL);
        context.init(presented, NodeIdentity.checkedAgainst(trusted.getTrustManagers(), nodeNames(settings)),
                null);
        return context;
    }

    /**
     * @param settings the settings being built from
     * @return every name a node of this cluster may be recognised by: its node id, and the host it was
     *         configured under
     * @throws AbortException if no nodes are configured, since then nothing would be recognised
     */
    private static Set<String> nodeNames(final Settings settings) throws AbortException {
        final Set<String> names = new LinkedHashSet<>();
        for (final Map.Entry<NodeId, InetSocketAddress> node : cluster(settings).entrySet()) {
            names.add(node.getKey().value());
            names.add(node.getValue().getHostString());
        }
        if (names.isEmpty()) {
            throw new AbortException("piplex: TLS is configured and no discas nodes are, so there is "
                    + "nothing to check the certificate a node presents against. Fill in 'discas "
                    + "nodes' in Manage Jenkins > System");
        }
        return names;
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
        return description(applied);
    }

    private static ClientDescription description(final Settings settings) {
        final String ownerId = settings.ownerId();
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

    // Called from every setter, and once more by configure() for the whole form -- which is why a
    // setter called while the form is being bound does nothing here and leaves it to that last call.
    // Inside a BulkChange, save() itself waits for the commit.
    private synchronized void changed() {
        if (binding) {
            return;
        }
        save();
    }

    // Package-private for the same reason as description(): what it parsed is otherwise observable
    // only by watching where a client dials.
    Map<NodeId, InetSocketAddress> cluster() throws AbortException {
        return cluster(applied);
    }

    private static Map<NodeId, InetSocketAddress> cluster(final Settings settings) throws AbortException {
        final String nodes = settings.nodes();
        final Map<NodeId, InetSocketAddress> cluster = new LinkedHashMap<>();
        if (nodes == null) {
            return cluster;
        }
        for (final String entry : nodes.split("[,\\s]+")) {
            if (entry.isBlank()) {
                continue;
            }
            final int equals = entry.indexOf('=');
            if (equals < 1 || equals == entry.length() - 1) {
                throw new AbortException(expected(entry));
            }
            final String address = entry.substring(equals + 1);
            // Split at the last colon rather than at every one: an IPv6 literal is mostly colons, and
            // the only way to write one that a host:port can be read out of is bracketed. After the
            // closing bracket is the one position the port can be in.
            final int colon = address.lastIndexOf(':');
            if (colon < 1 || colon < address.lastIndexOf(']')) {
                throw new AbortException(expected(entry));
            }
            // An unbracketed literal parses: `fe80::1` splits into the host `fe80:` and the port 1, and
            // the client then dials a name that does not resolve with a sentence about DNS. Refused
            // here, where the fix -- the brackets -- is what the message already says.
            if (address.indexOf(':') != colon && address.charAt(0) != '[') {
                throw new AbortException(expected(entry));
            }
            final NodeId node = NodeId.of(entry.substring(0, equals));
            // put() would keep the last of them and say nothing, so a cluster of three typed with one
            // id twice becomes a cluster of two -- and a quorum counted on the wrong number.
            if (cluster.containsKey(node)) {
                throw new AbortException("piplex: the discas node id '" + node.value()
                        + "' is listed more than once. Each node needs an id of its own, as "
                        + "nodeId=host:port");
            }
            cluster.put(node, new InetSocketAddress(host(address.substring(0, colon), entry),
                    port(address.substring(colon + 1), entry)));
        }
        return cluster;
    }

    // A bracketed IPv6 literal is unwrapped: the brackets are there to say where the address ends, and
    // InetSocketAddress wants the address without them.
    private static String host(final String text, final String entry) throws AbortException {
        if (text.charAt(0) != '[') {
            return text;
        }
        if (text.length() < 3 || text.charAt(text.length() - 1) != ']') {
            throw new AbortException(expected(entry));
        }
        return text.substring(1, text.length() - 1);
    }

    private static int port(final String text, final String entry) throws AbortException {
        final int port;
        try {
            port = Integer.parseInt(text);
        } catch (final NumberFormatException notANumber) {
            // Typed into a form by a person, so it is worth the same sentence the rest of the entry
            // gets rather than a NumberFormatException from somewhere down the stack.
            throw new AbortException(expected(entry));
        }
        // Refused here rather than by InetSocketAddress, which throws IllegalArgumentException past
        // every AbortException in this class and reaches the build as a stack trace.
        if (port < 1 || port > 65535) {
            throw new AbortException("piplex: the port in '" + entry + "' must be 1-65535, got " + port);
        }
        return port;
    }

    private static String expected(final String entry) {
        return "piplex: expected nodeId=host:port, got '" + entry
                + "'. An IPv6 address goes in brackets, as nodeId=[2001:db8::1]:7101";
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
