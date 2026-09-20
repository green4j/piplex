/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SecretBytes;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.CertificateCredentialsImpl;
import hudson.model.ParametersAction;
import hudson.model.Result;
import hudson.model.StringParameterValue;
import hudson.util.Secret;
import io.github.green4j.discas.common.client.auth.Pbkdf2;
import io.github.green4j.piplex.Environment;
import io.github.green4j.piplex.init.Acl;
import io.github.green4j.piplex.init.Estate;
import io.github.green4j.piplex.init.Jobs;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * The plugin against a discas node that actually enforces something.
 *
 * <p>Every other test in this module runs on an in-memory store, which answers every read and takes
 * every write. That is the right store for asking what the adapter does, and it cannot answer the
 * question this class exists for: authorization lives on the node, not in this repository -- there is
 * no ACL parser here to test -- so an ACL template that grants the wrong thing passes every test in
 * the module and fails on the night somebody runs the job.
 *
 * <p>Which is what happened. {@code work-narrowed.conf} shipped granting the controllers {@code G} on
 * the designation and the switch, and its own header says the operator acts as the controller -- so
 * every operator job it ships beside was refused, and nothing here noticed.
 *
 * <p>Both halves are generated from an {@link Estate} -- the ACL loaded into the node and the jobs
 * run against it -- so what is proved here is what a deployment gets, for the reason
 * {@link OperatorJobsTest} runs generated jobs rather than templates. Whether the generated ACL is
 * the one a person meant is a separate question, asked against three hand-written files in
 * {@code AclTest}; this one asks whether a real node admits it.
 *
 * <p>The node comes from the published image and is configured by the flags {@code docs/07-discas.md}
 * tells an operator to pass -- see {@link DiscasCluster}. Docker is required: a machine without it
 * has not run this suite and must not report the build as successful.
 */
@WithJenkins
class PiplexDiscasIntegrationTest {

    private static final String NODE_ALIAS = "node1";
    private static final String ENVIRONMENT = "prod";
    private static final String SWITCH_KEY = "eod-switch";
    private static final String DESIGNATION_KEY = "eod-owner";
    private static final String WORK_KEY = "eod";
    private static final String MILESTONE_KEY = "data/euc1";
    private static final String PASSWORD = "changeit";
    private static final String TOKEN = "a-shared-secret";

    /** The estate the shipped ACL files describe, and the one these jobs are generated from. */
    private static final Estate ESTATE = new Estate(Environment.of(ENVIRONMENT),
            List.of(new Estate.Controller("euc1-blue", "piplex-euc1-blue"),
                    new Estate.Controller("euc1-green", "piplex-euc1-green")),
            "n1=127.0.0.1:7101", Estate.Transport.ALLOWALL, Estate.SECRETS, Estate.Operator.SHARED,
            List.of(Estate.Work.of(WORK_KEY, SWITCH_KEY, DESIGNATION_KEY, null, MILESTONE_KEY)),
            Estate.Grants.PER_KEY);

    /** The same estate with the operator held apart, which is the other ACL layout. */
    private static final Estate SEPARATED = new Estate(ESTATE.environment(), ESTATE.controllers(),
            ESTATE.nodes(), Estate.Transport.MTLS, Estate.SECRETS,
            new Estate.Operator("piplex-ops", "piplex-ops-cert"), ESTATE.work(),
            Estate.Grants.PER_KEY);

    /** Where a generated ACL is put for the node to read. */
    @TempDir
    private Path generated;

    private DiscasCluster cluster;

    @AfterEach
    void stopTheNode() {
        if (cluster != null) {
            cluster.close();
            cluster = null;
        }
    }

    @Test
    void letsTheShippedJobsDoTheirWorkUnderTheShippedAcl(final JenkinsRule jenkins) throws Exception {
        start(DiscasCluster.enforcing(acl(ESTATE)));
        configure("euc1-blue");

        // Every write these jobs make, under the identity and the grants an operator would copy. A
        // grant left out fails the work closed, so a green build here is the template being usable.
        final WorkflowRun handedOver = job(jenkins, "acl-handover", "handover.groovy",
                "NEW_OWNER", "euc1-green", "REASON", "INC-4821");
        jenkins.assertBuildStatus(Result.SUCCESS, handedOver);
        jenkins.assertLogContains("'" + DESIGNATION_KEY + "' now designates 'euc1-green'", handedOver);

        final WorkflowRun stopped = job(jenkins, "acl-stop", "stop-work.groovy",
                "ACTION", "stop", "REASON", "INC-4821");
        jenkins.assertBuildStatus(Result.SUCCESS, stopped);
        jenkins.assertLogContains("Nothing it guards runs anywhere", stopped);

        final WorkflowRun drained = job(jenkins, "acl-drain", "drain-controller.groovy",
                "ACTION", "drain", "DRAIN_TIMEOUT", "30s");
        jenkins.assertBuildStatus(Result.SUCCESS, drained);
        jenkins.assertLogContains("is drained and quiet; maintenance can start", drained);
    }

    @Test
    void refusesAControllerTheWritesTheOperatorIdentityKeeps(final JenkinsRule jenkins) throws Exception {
        // The separated layout: the controllers read the designation and the switch, and only
        // piplex-ops writes them. A controller that tries is refused by the node, which is the whole
        // point of separating them -- and the failure an operator meets when the jobs were generated
        // for one layout and the cluster is running the other. The jobs below are generated from an
        // estate whose operator acts as the controller; the node is enforcing the one where it does
        // not.
        start(DiscasCluster.enforcing(acl(SEPARATED)));
        configure("euc1-blue");

        final WorkflowRun refused = job(jenkins, "acl-apart-handover", "handover.groovy",
                "NEW_OWNER", "euc1-green", "REASON", "INC-4830");

        jenkins.assertBuildStatus(Result.FAILURE, refused);
    }

    @Test
    void drainsItselfUnderTheSeparatedAclWithoutTheOperatorIdentity(final JenkinsRule jenkins) throws Exception {
        // The one narrow C a controller keeps in that layout: its own per-owner switch key. Taking a
        // controller out is a question about the processes running on it, so it is asked there --
        // and requiring the operations credential for it would put that credential on every
        // controller and undo the separation it exists for.
        start(DiscasCluster.enforcing(acl(SEPARATED)));
        configure("euc1-blue");

        final WorkflowRun drained = job(jenkins, "acl-apart-drain", "drain-controller.groovy",
                "ACTION", "drain", "DRAIN_TIMEOUT", "30s");

        jenkins.assertBuildStatus(Result.SUCCESS, drained);
        jenkins.assertLogContains("is drained and quiet; maintenance can start", drained);
    }

    @Test
    void reachesAClusterThatAsksForATokenOverTls(final JenkinsRule jenkins,
                                                 @TempDir final Path pki) throws Exception {
        final Path nodeStore = nodeCertificate(pki);
        final Path trusted = KeyStores.trustStoreFor(pki, nodeStore, NODE_ALIAS, PASSWORD);
        start(DiscasCluster.enforcing(acl(ESTATE))
                .tokenAuth(tokenStore(pki, "piplex-euc1-blue", TOKEN), nodeStore, trusted, PASSWORD));

        configure("euc1-blue");
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setTls(true);
        configuration.setTlsTruststore(trusted.toString());
        configuration.setTlsTruststorePassword(Secret.fromString(PASSWORD));
        configuration.setToken(Secret.fromString(TOKEN));

        // Nothing the plugin does differs here. What is being asked is whether the token it holds is
        // the thing the node admits it on, and whether a node told --client-auth token admits it at
        // all -- neither of which anything on this side of the wire can answer.
        final WorkflowRun drained = job(jenkins, "token-drain", "drain-controller.groovy",
                "ACTION", "drain", "DRAIN_TIMEOUT", "30s");

        jenkins.assertBuildStatus(Result.SUCCESS, drained);
        jenkins.assertLogContains("is drained and quiet; maintenance can start", drained);
    }

    @Test
    void refusesACertificateThatNamesSomebodyElse(final JenkinsRule jenkins,
                                                  @TempDir final Path pki) throws Exception {
        final Path nodeStore = nodeCertificate(pki);
        // One certificate per identity, each with the client id as its CN -- which under mTLS is the
        // only thing that decides what it may write, and what docs/07-discas.md says to issue.
        final Path opsStore = KeyStores.keyStoreHolding(pki, "piplex-ops", PASSWORD);
        final Path controllerStore = KeyStores.keyStoreHolding(pki, "piplex-euc1-blue", PASSWORD);
        start(DiscasCluster.enforcing(acl(SEPARATED))
                .mutualTls(nodeStore,
                        KeyStores.trustStoreOf(pki, "node-trust", PASSWORD,
                                List.of("piplex-ops", "piplex-euc1-blue"),
                                List.of(opsStore, controllerStore)),
                        PASSWORD));

        configure("euc1-blue");
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setTls(true);
        configuration.setTlsKeystore(controllerStore.toString());
        configuration.setTlsKeystorePassword(Secret.fromString(PASSWORD));
        configuration.setTlsTruststore(
                KeyStores.trustStoreFor(pki, nodeStore, NODE_ALIAS, PASSWORD).toString());
        configuration.setTlsTruststorePassword(Secret.fromString(PASSWORD));
        certificate(jenkins, "piplex-ops-cert", opsStore);

        // The block claims one identity and presents the certificate of another. The node would grant
        // what the certificate says and the build would record the other name -- so whichever of the
        // two is wrong, this must not be allowed to run.
        final WorkflowRun refused = build(jenkins, "mtls-wrong-name", """
                withPiplexOperator(credentialsId: 'piplex-ops-cert', clientId: 'piplex-euc1-blue') {
                    piplexDesignate key: 'eod-owner', owner: 'euc1-green', reason: 'INC-1'
                }
                """);

        jenkins.assertBuildStatus(Result.FAILURE, refused);
        jenkins.assertLogContains("acts as 'piplex-euc1-blue', but its credential presents a "
                + "certificate for 'piplex-ops'", refused);

        // And the same block, named as the certificate is, does the work the ACL keeps for it.
        final WorkflowRun handedOver = build(jenkins, "mtls-right-name", """
                withPiplexOperator(credentialsId: 'piplex-ops-cert', clientId: 'piplex-ops') {
                    piplexDesignate key: 'eod-owner', owner: 'euc1-green', reason: 'INC-2'
                }
                """);

        jenkins.assertBuildStatus(Result.SUCCESS, handedOver);
        jenkins.assertLogContains("DESIGNATING key=eod-owner owner=euc1-green as=piplex-ops",
                handedOver);
    }

    /**
     * Starts the node and waits for it to serve.
     *
     * <p>No check for Docker and no skip without one: a machine with none has not run these tests,
     * and a suite that reports success for a run it did not make is how the broken ACL shipped.
     *
     * @param node how the node is to be configured
     */
    private void start(final DiscasCluster.Builder node) {
        cluster = node.start();
    }

    /**
     * The node's own certificate.
     *
     * <p>Named for both the address and the host the container is reached at, because this
     * controller checks that the certificate belongs to a node it was configured to talk to rather
     * than merely to somebody a trust store vouches for -- which is what {@link NodeIdentity} is for.
     *
     * @param pki where to write it
     * @return the key store
     * @throws Exception if keytool cannot be run
     */
    private static Path nodeCertificate(final Path pki) throws Exception {
        return KeyStores.keyStoreHolding(pki, NODE_ALIAS, PASSWORD, "CN=" + NODE_ALIAS,
                "dns:localhost,ip:127.0.0.1");
    }

    /**
     * A token store in the format {@code --client-token-file} reads.
     *
     * <p>Hashed here rather than written in the clear, because that is the format: the node keeps
     * PBKDF2 hashes and never the token, which is half of why rotating one means editing this file
     * as well as every controller.
     *
     * @param directory where to write it
     * @param clientId  who the token admits
     * @param token     the secret that controller holds
     * @return the file written
     * @throws Exception if it cannot be written
     */
    private static Path tokenStore(final Path directory, final String clientId, final String token)
            throws Exception {
        final byte[] salt = Pbkdf2.newSalt();
        final byte[] hash = Pbkdf2.hash(token, salt, Pbkdf2.DEFAULT_ITERATIONS);
        final Base64.Encoder base64 = Base64.getEncoder();
        final Path file = directory.resolve("tokens.conf");
        Files.writeString(file, "client." + clientId + " = pbkdf2$" + Pbkdf2.DEFAULT_ITERATIONS
                + "$" + base64.encodeToString(salt) + "$" + base64.encodeToString(hash)
                + "$" + (System.currentTimeMillis() + Duration.ofHours(1L).toMillis()) + "\n",
                StandardCharsets.UTF_8);
        return file;
    }

    /**
     * Installs a certificate credential holding a PKCS12 file, as the Jenkins UI would.
     *
     * @param jenkins the controller
     * @param id      the credential id a Jenkinsfile names
     * @param file    the key store
     * @throws Exception if the credential cannot be saved
     */
    private static void certificate(final JenkinsRule jenkins, final String id, final Path file)
            throws Exception {
        SystemCredentialsProvider.getInstance().getDomainCredentialsMap().put(Domain.global(),
                List.of(new CertificateCredentialsImpl(CredentialsScope.GLOBAL, id, "for tests",
                        PASSWORD, new CertificateCredentialsImpl.UploadedKeyStoreSource(
                                SecretBytes.fromBytes(Files.readAllBytes(file))))));
        jenkins.jenkins.save();
    }

    private WorkflowRun build(final JenkinsRule jenkins, final String name, final String script)
            throws Exception {
        final WorkflowJob project = jenkins.createProject(WorkflowJob.class, name);
        project.setDefinition(new CpsFlowDefinition(script, true));
        return project.scheduleBuild2(0).get();
    }

    // Written out rather than passed in memory: the node reads a file, and a file is also what an
    // operator copies to it.
    private Path acl(final Estate estate) throws IOException {
        final Path file = generated.resolve(
                estate.operator().apart() ? "operator-apart.conf" : "work-narrowed.conf");
        Files.writeString(file, Acl.render(estate), StandardCharsets.UTF_8);
        return file;
    }

    /**
     * Points this controller at the node, as a person would in Manage Jenkins.
     *
     * @param ownerId this controller's name, which is also the identity the ACL grants
     */
    private void configure(final String ownerId) {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setOwnerId(ownerId);
        // The client id the ACL file names. Spelled out rather than left to default to the owner id,
        // because that agreement is the thing being tested.
        configuration.setClientId("piplex-" + ownerId);
        configuration.setEnvironment(ENVIRONMENT);
        configuration.setNodes(cluster.nodes());
    }

    private WorkflowRun job(final JenkinsRule jenkins, final String name, final String file,
                            final String... parameters) throws Exception {
        final WorkflowJob project = jenkins.createProject(WorkflowJob.class, name);
        // Generated the way a deployment generates it, from the estate the shipped ACL files
        // describe: what runs against a real node is the job an operator would have installed.
        project.setDefinition(new CpsFlowDefinition(
                Jobs.render(ESTATE, ESTATE.controllers().get(0), file), true));
        final Map<String, String> given = new LinkedHashMap<>();
        given.put("WORK", WORK_KEY);
        given.put("SWITCH_KEY", SWITCH_KEY);
        for (int i = 0; i < parameters.length; i += 2) {
            given.put(parameters[i], parameters[i + 1]);
        }
        final List<StringParameterValue> values = new ArrayList<>();
        given.forEach((of, value) -> values.add(new StringParameterValue(of, value)));
        return project.scheduleBuild2(0, new ParametersAction(new ArrayList<>(values))).get();
    }
}
