/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import hudson.AbortException;
import io.github.green4j.discas.common.KvLimits;
import io.github.green4j.discas.common.identity.NodeId;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What operator input turns into on its way to a cluster. Nothing here reaches a build log, so left
 * unasserted its first reader is a controller trying to connect.
 */
@WithJenkins
class PiplexConfigurationTest {

    /** Two chars, four UTF-8 bytes. */
    private static final String ASTRAL = new String(Character.toChars(0x1F680));
    private static final String PREFIX = "piplex Jenkins plugin";

    @Test
    void readsTheClusterTheWayTheFormLetsItBeTyped(final JenkinsRule jenkins) throws Exception {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        // The leading comma is a stray an operator leaves behind, not a fourth node.
        final String typed = ",n1=10.0.0.1:7101,  n2=10.0.0.2:7101\nn3=discas-3.internal:7102";
        configuration.setNodes(typed);
        assertEquals(typed, configuration.getNodes());

        final Map<NodeId, InetSocketAddress> cluster = configuration.cluster();

        // Order is what a client dials in, and an operator who put the nearest node first meant it.
        assertEquals(List.of(NodeId.of("n1"), NodeId.of("n2"), NodeId.of("n3")),
                List.copyOf(cluster.keySet()));
        assertEquals("10.0.0.1", cluster.get(NodeId.of("n1")).getHostString());
        assertEquals(7101, cluster.get(NodeId.of("n1")).getPort());
        assertEquals("discas-3.internal", cluster.get(NodeId.of("n3")).getHostString());
        assertEquals(7102, cluster.get(NodeId.of("n3")).getPort());
    }

    @Test
    void readsNoClusterFromAFieldNobodyFilledIn(final JenkinsRule jenkins) throws Exception {
        // Empty, not an error: saying what to configure is build()'s job and it names the page.
        assertTrue(PiplexConfiguration.get().cluster().isEmpty());
    }

    @Test
    void refusesAnEntryThatIsNotANodeIdAHostAndAPort(final JenkinsRule jenkins) {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setNodes("n1=10.0.0.1:7101, 10.0.0.2:7101");

        final AbortException refused = assertThrows(AbortException.class, configuration::cluster);

        // Which entry, not just the shape: three are typed on one line.
        assertTrue(refused.getMessage().contains("10.0.0.2:7101"), refused.getMessage());
    }

    @Test
    void refusesAPortThatIsNotANumber(final JenkinsRule jenkins) {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setNodes("n1=10.0.0.1:seven");

        final AbortException refused = assertThrows(AbortException.class, configuration::cluster);

        assertTrue(refused.getMessage().contains("n1=10.0.0.1:seven"), refused.getMessage());
        assertTrue(refused.getMessage().contains("nodeId=host:port"), refused.getMessage());
    }

    @Test
    void leavesTheWatchPollPeriodToTheClientUntilSomebodySetsOne(final JenkinsRule jenkins)
            throws Exception {
        final PiplexConfiguration configuration = PiplexConfiguration.get();

        // Null is not "no polling": it is the discas client's own setting, which is the only one
        // allowed below the floor and which an operator may have set deliberately.
        assertNull(configuration.watchPollPeriod());

        configuration.setWatchPollPeriod("30s");
        assertEquals(Duration.ofSeconds(30), configuration.watchPollPeriod());

        configuration.setWatchPollPeriod("  2m  ");
        assertEquals(Duration.ofMinutes(2), configuration.watchPollPeriod());

        configuration.setWatchPollPeriod("PT1M30S");
        assertEquals(Duration.ofSeconds(90), configuration.watchPollPeriod());

        configuration.setWatchPollPeriod("   ");
        assertNull(configuration.watchPollPeriod());
    }

    @Test
    void refusesAWatchPollPeriodBelowWhatDiscasAllows(final JenkinsRule jenkins) throws Exception {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        // The floor is 500ms, and only the discas client's own configuration may go under it.
        configuration.setWatchPollPeriod("PT0.5S");
        assertEquals(Duration.ofMillis(500), configuration.watchPollPeriod());

        configuration.setWatchPollPeriod("PT0.2S");

        final AbortException refused = assertThrows(AbortException.class,
                configuration::watchPollPeriod);

        // Refused when the configuration is read, not by the first watch four hours into a park.
        assertTrue(refused.getMessage().contains("500"), refused.getMessage());
        assertTrue(refused.getMessage().contains("Manage Jenkins"), refused.getMessage());
    }

    @Test
    void namesTheFieldWhenTheWatchPollPeriodIsNotADuration(final JenkinsRule jenkins) {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setWatchPollPeriod("often");

        final AbortException refused = assertThrows(AbortException.class,
                configuration::watchPollPeriod);

        assertTrue(refused.getMessage().contains("watchPollPeriod"), refused.getMessage());
        assertTrue(refused.getMessage().contains("often"), refused.getMessage());
    }

    @Test
    void dropsTheWhitespaceAroundWhatWasTypedIn(final JenkinsRule jenkins) {
        final PiplexConfiguration configuration = PiplexConfiguration.get();

        configuration.setOwnerId("  euc1-blue  ");
        assertEquals("euc1-blue", configuration.getOwnerId());

        configuration.setClientId("  piplex-euc1  ");
        assertEquals("piplex-euc1", configuration.getClientId());

        // requireOwnerId tests for null, not for blankness.
        configuration.setOwnerId("   ");
        assertNull(configuration.getOwnerId());
    }

    @Test
    void saysWhereToSetTheOwnerIdWhenItIsMissing(final JenkinsRule jenkins) throws Exception {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setOwnerId(null);

        assertTrue(assertThrows(AbortException.class, configuration::requireOwnerId)
                .getMessage().contains("Manage Jenkins"));

        configuration.setOwnerId("euc1-blue");
        assertEquals("euc1-blue", configuration.requireOwnerId());
    }

    @Test
    void tellsTheClusterWhichControllerIsConnecting(final JenkinsRule jenkins) {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setOwnerId("euc1-blue");

        final String described = configuration.description().value();

        assertTrue(described.startsWith(PREFIX), described);
        assertTrue(described.contains("euc1-blue"), described);
    }

    @Test
    void stillDescribesItselfBeforeAnybodyHasSetAnOwnerId(final JenkinsRule jenkins) {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setOwnerId(null);

        assertEquals(PREFIX, configuration.description().value());
    }

    @Test
    void keepsALongOwnerIdInsideWhatDiscasWillAccept(final JenkinsRule jenkins) {
        final PiplexConfiguration configuration = PiplexConfiguration.get();

        // A range rather than one length chosen to land badly: which one does depends on how many
        // bytes the prefix takes, and pinning that would pin today's wording of it.
        for (int characters = 50; characters <= 70; characters++) {
            configuration.setOwnerId(ASTRAL.repeat(characters));

            // ClientDescription.of throws over the limit, so getting a value back is half of it.
            final String described = configuration.description().value();

            assertTrue(KvLimits.utf8Length(described) <= KvLimits.MAX_CLIENT_DESCRIPTION_BYTES,
                    characters + ": " + KvLimits.utf8Length(described) + " bytes");
            assertTrue(described.startsWith(PREFIX), described);
            // Half a surrogate pair encodes as '?', so the cluster would be shown something other
            // than what was sent. A round trip through UTF-8 is what says it is still whole.
            assertEquals(described,
                    new String(described.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8),
                    characters + ": cut through a character");
        }
    }
}
