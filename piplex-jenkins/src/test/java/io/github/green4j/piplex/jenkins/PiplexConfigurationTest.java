/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import hudson.AbortException;
import hudson.BulkChange;
import hudson.ExtensionList;
import hudson.XmlFile;
import hudson.model.Descriptor.FormException;
import hudson.model.Saveable;
import hudson.model.listeners.SaveableListener;
import io.github.green4j.discas.common.KvLimits;
import io.github.green4j.discas.common.identity.NodeId;
import io.github.green4j.piplex.store.CoordinationStore;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.StaplerRequest2;

import java.io.File;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
        // Empty, not an error: saying what to configure is build()'s job and it names the page.
        assertTrue(configuration.cluster().isEmpty());

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
    void countsAWholeFormAsOneChange(final JenkinsRule jenkins) throws Exception {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setOwnerId("euc1-blue");
        configuration.setNodes("n1=10.0.0.1:7101, n2=10.0.0.2:7101");
        configuration.setWatchPollPeriod("30s");

        final AtomicInteger saved = saves(configuration);

        jenkins.configRoundtrip();

        // Every setter saves on its own, and one Save binds ten of them. Counted per field, a step
        // asking for the store between two of them gets one built from half a form.
        assertEquals(1, saved.get(), "One Save of the form is one change, whatever it touched");
        assertEquals("euc1-blue", configuration.getOwnerId());
        assertEquals("30s", configuration.getWatchPollPeriod());
    }

    @Test
    void publishesAReloadToBuildsOnlyWhenItIsCommitted(final JenkinsRule jenkins) throws Exception {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setOwnerId("euc1-blue");
        configuration.setNodes("n1=10.0.0.1:7101");

        final AtomicInteger saved = saves(configuration);

        // How Configuration as Code applies its setters: one at a time, inside a BulkChange.
        try (BulkChange reload = new BulkChange(configuration)) {
            configuration.setOwnerId("eus1-blue");
            assertEquals("euc1-blue", configuration.requireOwnerId(),
                    "A build must not see half a reload");
            configuration.setNodes("n2=10.0.0.2:7101");
            assertEquals(List.of(NodeId.of("n1")), List.copyOf(configuration.cluster().keySet()));
            assertEquals(0, saved.get());
            reload.commit();
        }

        assertEquals(1, saved.get(), "One reload is one change, whatever it touched");
        assertEquals("eus1-blue", configuration.requireOwnerId());
        assertEquals(List.of(NodeId.of("n2")), List.copyOf(configuration.cluster().keySet()));
    }

    @Test
    void putsAFormThatCannotBeBoundBackAsItWas(final JenkinsRule jenkins) throws Exception {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setOwnerId("euc1-blue");
        configuration.setNodes("n1=10.0.0.1:7101");
        final IllegalArgumentException unbindable = new IllegalArgumentException("Bad form");
        // Half of the form goes in before the bind fails, which is what a bad field late in it does.
        final StaplerRequest2 request = (StaplerRequest2) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {StaplerRequest2.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("bindJSON")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    configuration.setOwnerId("eus1-blue");
                    throw unbindable;
                });

        assertSame(unbindable, assertThrows(IllegalArgumentException.class,
                () -> configuration.configure(request, new JSONObject())));

        assertEquals("euc1-blue", configuration.getOwnerId(),
                "A form that could not be bound must leave the settings the store was built from");
        assertEquals("n1=10.0.0.1:7101", configuration.getNodes());
    }

    @Test
    void refusesAClusterItCouldNotConnectTo(final JenkinsRule jenkins) {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        final Map<String, List<String>> table = Map.of(
                // Which entry, not just the shape: several are typed on one line.
                "n1=10.0.0.1:7101, 10.0.0.2:7101", List.of("10.0.0.2:7101"),
                "n1=10.0.0.1:seven", List.of("n1=10.0.0.1:seven", "nodeId=host:port"),
                // A number that is not a port would throw past every AbortException, as a stack trace.
                "n1=10.0.0.1:70000", List.of("n1=10.0.0.1:70000", "65535"),
                // It parses, which is the trouble: the host "fe80:" and the port 1, and then a sentence
                // about DNS. The message says what to do instead.
                "n1=fe80::1", List.of("brackets"),
                // Kept the last of them, a cluster of three typed with one id twice was a cluster of two,
                // and a quorum counted on the wrong number.
                "n1=10.0.0.1:7101, n2=10.0.0.2:7101, n1=10.0.0.3:7101", List.of("'n1'"));

        table.forEach((nodes, said) -> {
            final String refused = refused(() -> configuration.setNodes(nodes));
            for (final String fragment : said) {
                assertTrue(refused.contains(fragment), refused);
            }
        });
    }

    @Test
    void readsAnIpV6NodeTheOnlyWayOneCanBeWritten(final JenkinsRule jenkins) throws Exception {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setNodes("n1=[2001:db8::1]:7101, n2=[::1]:7102");

        final Map<NodeId, InetSocketAddress> cluster = configuration.cluster();

        // Brackets say where the address ends, and the port is what follows the last colon after them.
        // Split at every colon instead -- which is what an IPv4-shaped parser does -- and an operator
        // who typed a perfectly good cluster is told it is not one.
        assertEquals("2001:db8:0:0:0:0:0:1", cluster.get(NodeId.of("n1")).getHostString());
        assertEquals(7101, cluster.get(NodeId.of("n1")).getPort());
        assertEquals(7102, cluster.get(NodeId.of("n2")).getPort());
    }

    @Test
    void keepsTheSavedSettingsWhenTheNewOnesCannotBeWritten(final JenkinsRule jenkins) throws Exception {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setOwnerId("euc1-blue");

        final AtomicInteger saved = saves(configuration);
        // A directory where the file goes: the atomic write cannot move its temp file over it.
        final File file = new File(jenkins.jenkins.getRootDir(), configuration.getId() + ".xml");
        assertTrue(file.delete() || !file.exists());
        assertTrue(file.mkdir());

        assertThrows(UncheckedIOException.class, () -> configuration.setOwnerId("eus1-blue"));

        // What a restart would read back is the only identity a build may act under.
        assertEquals("euc1-blue", configuration.requireOwnerId());
        assertEquals("euc1-blue", configuration.getOwnerId(), "The unsaved value must not linger");
        assertEquals(0, saved.get());
    }

    @Test
    void keepsTheTimersARunInFlightIsHoldingWhenASettingChanges(final JenkinsRule jenkins) {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        final ScheduledExecutorService scheduler = configuration.scheduler();

        configuration.setOwnerId("euc1-blue");
        configuration.setNodes("n1=10.0.0.1:7101");

        // Changing a setting closes the store, and that is how a run in flight is stopped: its next
        // renewal reaches a closed store, fails, and the run is revoked once its grace period is out.
        // Take the scheduler away with the store and there is no next renewal -- nothing fails, nothing
        // is revoked, and the run carries on holding work another controller is already free to take.
        assertSame(scheduler, configuration.scheduler(), "The timers must outlive the store");
        assertFalse(scheduler.isShutdown(), "A run in flight still has its renewal on this");
    }

    @Test
    void letsGoOfTheStoreAndTheTimersWhenJenkinsStops(final JenkinsRule jenkins) throws Exception {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setOwnerId("euc1-blue");
        configuration.setNodes("n1=10.0.0.1:7101");
        final CoordinationStore store = configuration.store();
        final ScheduledExecutorService scheduler = configuration.scheduler();

        PiplexConfiguration.stopping();

        // In a JVM that outlives Jenkins, these would keep renewing a stopped controller's leases.
        assertTrue(scheduler.isShutdown(), "The timers must stop with Jenkins");
        final ExecutionException closed = assertThrows(ExecutionException.class,
                () -> store.get("piplex/any").toCompletableFuture().get(10L, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, closed.getCause());
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
    void refusesAWatchPollPeriodDiscasWouldNotTake(final JenkinsRule jenkins) throws Exception {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        // The floor is 500ms, and only the discas client's own configuration may go under it.
        configuration.setWatchPollPeriod("PT0.5S");
        assertEquals(Duration.ofMillis(500), configuration.watchPollPeriod());

        // Refused when it is saved, not by the first watch four hours into a park.
        final String refused = refused(() -> configuration.setWatchPollPeriod("PT0.2S"));

        assertTrue(refused.contains("500"), refused);
        assertTrue(refused.contains("Manage Jenkins"), refused);
        assertEquals(Duration.ofMillis(500), configuration.watchPollPeriod());

        final String notADuration = refused(() -> configuration.setWatchPollPeriod("often"));
        assertTrue(notADuration.contains("watchPollPeriod"), notADuration);
        assertTrue(notADuration.contains("often"), notADuration);
    }

    @Test
    void readsWhatWasTypedInWithoutTheWhitespaceAroundIt(final JenkinsRule jenkins) throws Exception {
        final PiplexConfiguration configuration = PiplexConfiguration.get();

        configuration.setOwnerId("  euc1-blue  ");
        assertEquals("euc1-blue", configuration.requireOwnerId());

        configuration.setClientId("  piplex-euc1  ");
        assertEquals("piplex-euc1", configuration.getClientId());

        // Blank is missing, and missing says where to set it.
        configuration.setOwnerId("   ");
        assertNull(configuration.getOwnerId());
        assertTrue(assertThrows(AbortException.class, configuration::requireOwnerId)
                .getMessage().contains("Manage Jenkins"));
    }

    @Test
    void keepsTheStoreInUseWhenASettingIsRefused(final JenkinsRule jenkins) throws Exception {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setOwnerId("euc1-blue");
        configuration.setNodes("n1=10.0.0.1:7101");
        final Object store = configuration.store();

        final AtomicInteger saved = saves(configuration);

        // The core escapes each part of the lease identity, so this is not about who is let in: the same
        // string names the controller in every record and log line, and is cheaper to catch here.
        final String refused = refused(() -> configuration.setOwnerId("team/euc1-blue"));
        assertTrue(refused.contains("must not contain '/'"), refused);

        // Saved, it closed the store every run in flight renews through, and the typo was found only
        // by the next build -- after the runs it had revoked.
        assertSame(store, configuration.store(), "A typo must not revoke runs");
        assertEquals("euc1-blue", configuration.getOwnerId(), "The refused value must not linger");
        assertEquals(0, saved.get());
    }

    @Test
    void namesTheFieldARefusedFormGotWrong(final JenkinsRule jenkins) {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        configuration.setOwnerId("euc1-blue");
        final StaplerRequest2 request = (StaplerRequest2) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {StaplerRequest2.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("bindJSON")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    configuration.setOwnerId("team/euc1-blue");
                    configuration.setNodes("n1=10.0.0.1:7101");
                    return null;
                });

        final FormException refused = assertThrows(FormException.class,
                () -> configuration.configure(request, new JSONObject()));

        // The page shows it beside the field rather than as a stack trace.
        assertEquals("ownerId", refused.getFormField());
        assertEquals("euc1-blue", configuration.getOwnerId());
        assertNull(configuration.getNodes(), "The whole form is refused, not just the field");
    }

    @Test
    void tellsTheClusterWhichControllerIsConnecting(final JenkinsRule jenkins) {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        assertEquals(PREFIX, configuration.description().value(), "Also before anybody has set an owner id");

        configuration.setOwnerId("euc1-blue");
        final String described = configuration.description().value();

        assertTrue(described.startsWith(PREFIX), described);
        assertTrue(described.contains("euc1-blue"), described);
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

    private static AtomicInteger saves(final PiplexConfiguration configuration) {
        final AtomicInteger saved = new AtomicInteger();
        ExtensionList.lookup(SaveableListener.class).add(new SaveableListener() {
            @Override
            public void onChange(final Saveable what, final XmlFile file) {
                if (what == configuration) {
                    saved.incrementAndGet();
                }
            }
        });
        return saved;
    }

    private static String refused(final Runnable setting) {
        return assertThrows(IllegalArgumentException.class, setting::run).getMessage();
    }
}
