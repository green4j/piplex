/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import hudson.AbortException;
import io.github.green4j.piplex.exclusive.Revocation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.ObjectStreamField;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a running build has written down, as each released {@link ExclusiveStepExecution#STATE_VERSION}
 * wrote it, read by this code.
 *
 * <p>{@code state/v<N>/} is recorded once, by {@code ./gradlew :piplex-jenkins:recordStateFixtures},
 * while N is the current version, and committed. It is never recorded again after N is released:
 * bytes made by the code under test would only ever agree with it.
 */
class StateFixturesTest {

    static final String RECORD_INTO = "piplex.recordStateFixtures";
    private static final String RECORD = "./gradlew :piplex-jenkins:recordStateFixtures";
    private static final String SCHEMA = "schema.txt";

    private static final String OWNERSHIP_ID = "eod-job#12/7/3f0c9a1e";
    private static final long NANOS_WAITED = Duration.ofSeconds(4).toNanos();

    @Test
    void hasFixturesForTheCurrentVersion() {
        assertNotNull(fixture(ExclusiveStepExecution.STATE_VERSION, SCHEMA),
                "No fixtures for state version " + ExclusiveStepExecution.STATE_VERSION
                        + ". Run " + RECORD + " and commit what it writes");
        assertNull(ExclusiveStepExecution.unknownState(ExclusiveStepExecution.STATE_VERSION, "eod"));
        // Written before the version was kept: the checks in onResume() read it.
        assertNull(ExclusiveStepExecution.unknownState(0, "eod"));
    }

    @Test
    void refusesStateWrittenByANewerVersion() {
        final AbortException refused =
                ExclusiveStepExecution.unknownState(ExclusiveStepExecution.STATE_VERSION + 1, "eod");

        assertNotNull(refused, "A downgraded plugin must not guess what newer state means");
        assertTrue(refused.getMessage().contains("older version"), refused.getMessage());
    }

    @Test
    void changesTheSavedStateOnlyByAddingToIt() throws Exception {
        final byte[] schema = read(ExclusiveStepExecution.STATE_VERSION, SCHEMA);
        final Map<String, List<String>> recorded = parse(new String(schema, StandardCharsets.UTF_8));
        final Map<String, List<String>> current = parse(schema());

        // A field added reads as its default from older state, which the class says is safe or is not.
        // Anything else changes what old state means, and old state is only read by a version that knows.
        for (final Map.Entry<String, List<String>> type : recorded.entrySet()) {
            final List<String> now = current.get(type.getKey());
            final List<String> gone = new ArrayList<>(type.getValue());
            if (now != null) {
                gone.removeAll(now);
            }
            assertTrue(now != null && gone.isEmpty(),
                    "The saved form of " + type.getKey() + " lost " + (now == null ? "the class" : gone)
                            + " without STATE_VERSION being raised. Raise it, teach onResume() the old "
                            + "form, and run " + RECORD);
        }
    }

    @Test
    void readsVersion1() throws Exception {
        final ExclusiveStepExecution asking = execution(1, "asking.ser");
        assertEquals("eod", field(asking, "key"));
        assertEquals("uat", field(asking, "environment"));
        assertEquals("7/3f0c9a1e", field(asking, "executionId"));
        assertEquals(OWNERSHIP_ID, field(asking, "ownershipId"));
        assertEquals("eod", field(asking, "designatedBy"));
        assertEquals("2026-09-12", field(asking, "generation"));
        assertEquals("eod", field(asking, "completedWhen"));
        assertEquals("eod", field(asking, "enabledBy"));
        assertEquals("/dc/active", field(asking, "activeWhenKey"));
        assertEquals("euc1-blue", field(asking, "activeWhenValue"));
        assertEquals("60s", field(asking, "lease"));
        assertEquals("10s", field(asking, "renewEvery"));
        assertEquals("30s", field(asking, "renewalGrace"));
        assertEquals("20s", field(asking, "guardGrace"));
        assertEquals("15m", field(asking, "handoverWait"));
        assertEquals(1, field(asking, "stateVersion"));
        assertNull(ExclusiveStepExecution.unknownState((int) field(asking, "stateVersion"), "eod"));
        // A park resumed goes on with the handoverWait that is left, not a whole one.
        assertEquals(Duration.ofSeconds(4), asking.waited());
        assertEquals(0L, field(asking, "admittedToken"));
        assertEquals(false, field(asking, "bodyEnded"));

        // The body ran under token 7: a resumed ask that is given another one stops it.
        final ExclusiveStepExecution running = execution(1, "running.ser");
        assertEquals(7L, field(running, "admittedToken"));
        assertEquals(false, field(running, "bodyEnded"));
        assertNull(field(running, "bodyFailure"));

        // The body is over and failed: a resumed step answers with this rather than asking again.
        final ExclusiveStepExecution ended = execution(1, "ended.ser");
        assertEquals(true, field(ended, "bodyEnded"));
        final Throwable failure = (Throwable) field(ended, "bodyFailure");
        assertNotNull(failure);
        assertTrue(failure.getMessage().contains("eod"), failure.getMessage());

        final PiplexOwnership ownership = (PiplexOwnership) deserialize(read(1, "ownership.ser"));
        assertEquals("eod", ownership.key());
        assertEquals(OWNERSHIP_ID, field(ownership, "id"));
    }

    /**
     * Not part of the build: run by {@value #RECORD}, which names the directory to write to.
     */
    @Test
    @Tag("record-state-fixtures")
    void recordsTheCurrentVersion() throws Exception {
        final String into = System.getProperty(RECORD_INTO);
        assertNotNull(into, "Run " + RECORD + " rather than this test on its own");
        final Path dir = Path.of(into, "v" + ExclusiveStepExecution.STATE_VERSION);
        assertFalse(Files.exists(dir), dir + " is recorded already, and a recorded version is never "
                + "recorded again. Raise STATE_VERSION if the saved form changed");
        Files.createDirectories(dir);

        final ExclusiveStepExecution asking = sample();
        set(asking, "waitedNanos", NANOS_WAITED);
        Files.write(dir.resolve("asking.ser"), serialize(asking));

        final ExclusiveStepExecution running = sample();
        set(running, "admittedToken", 7L);
        Files.write(dir.resolve("running.ser"), serialize(running));

        final ExclusiveStepExecution ended = sample();
        set(ended, "admittedToken", 7L);
        set(ended, "bodyEnded", true);
        final Revocation handedOver = new Revocation(Revocation.Reason.DESIGNATION_CHANGED, "euc1-green");
        set(ended, "bodyFailure", PiplexInterruption.revoked("eod", handedOver));
        Files.write(dir.resolve("ended.ser"), serialize(ended));

        Files.write(dir.resolve("ownership.ser"), serialize(new PiplexOwnership("eod", OWNERSHIP_ID)));
        Files.writeString(dir.resolve(SCHEMA), schema());
    }

    private static ExclusiveStepExecution sample() throws Exception {
        // Made the way a read-back one is, without a constructor: that one needs a running build.
        final Constructor<?> bare = (Constructor<?>) Class.forName("sun.reflect.ReflectionFactory")
                .getMethod("newConstructorForSerialization", Class.class, Constructor.class)
                .invoke(invokeStatic("sun.reflect.ReflectionFactory", "getReflectionFactory"),
                        ExclusiveStepExecution.class, Object.class.getDeclaredConstructor());
        final ExclusiveStepExecution execution = (ExclusiveStepExecution) bare.newInstance();
        set(execution, "key", "eod");
        // Not the default: what this pins is that a resumed step comes back in the environment it was
        // admitted in, and a fixture recorded in the default one could not tell the two apart.
        set(execution, "environment", "uat");
        set(execution, "executionId", "7/3f0c9a1e");
        set(execution, "ownershipId", OWNERSHIP_ID);
        set(execution, "designatedBy", "eod");
        set(execution, "generation", "2026-09-12");
        set(execution, "completedWhen", "eod");
        set(execution, "enabledBy", "eod");
        set(execution, "activeWhenKey", "/dc/active");
        set(execution, "activeWhenValue", "euc1-blue");
        set(execution, "lease", "60s");
        set(execution, "renewEvery", "10s");
        set(execution, "renewalGrace", "30s");
        set(execution, "guardGrace", "20s");
        set(execution, "handoverWait", "15m");
        set(execution, "stateVersion", ExclusiveStepExecution.STATE_VERSION);
        return execution;
    }

    /**
     * @return every class of this plugin that is written down, with the fields it is written down with:
     *         Java serialisation for what a running build keeps, XStream for the global configuration
     */
    private static String schema() throws Exception {
        final Map<String, List<String>> types = new TreeMap<>();
        for (final Class<?> type : pluginClasses()) {
            if (type == PiplexConfiguration.class) {
                types.put(type.getName(), persistedFields(type));
            } else if (Serializable.class.isAssignableFrom(type) && !type.isInterface()) {
                final ObjectStreamClass form = ObjectStreamClass.lookup(type);
                final List<String> fields = new ArrayList<>();
                fields.add("serialVersionUID " + form.getSerialVersionUID());
                for (final ObjectStreamField field : form.getFields()) {
                    final String of = field.isPrimitive()
                            ? String.valueOf(field.getTypeCode())
                            : field.getTypeString();
                    fields.add(field.getName() + ' ' + of);
                }
                types.put(type.getName(), fields);
            }
        }
        final StringBuilder text = new StringBuilder();
        types.forEach((type, fields) -> {
            text.append(type).append('\n');
            fields.forEach(field -> text.append("    ").append(field).append('\n'));
        });
        return text.toString();
    }

    private static List<String> persistedFields(final Class<?> type) {
        final List<String> fields = new ArrayList<>();
        for (final Field field : type.getDeclaredFields()) {
            final int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) && !Modifier.isTransient(modifiers)) {
                fields.add(field.getName() + ' ' + field.getType().getName());
            }
        }
        fields.sort(null);
        return fields;
    }

    private static Map<String, List<String>> parse(final String schema) {
        final Map<String, List<String>> types = new TreeMap<>();
        List<String> fields = null;
        for (final String line : schema.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith(" ")) {
                fields.add(line.strip());
            } else {
                fields = new ArrayList<>();
                types.put(line.strip(), fields);
            }
        }
        return types;
    }

    private static List<Class<?>> pluginClasses() throws Exception {
        final URI where = ExclusiveStepExecution.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI();
        final Path root = Path.of(where);
        if (Files.isDirectory(root)) {
            return classesUnder(root);
        }
        try (FileSystem jar = FileSystems.newFileSystem(root)) {
            return classesUnder(jar.getPath("/"));
        }
    }

    private static List<Class<?>> classesUnder(final Path root) throws Exception {
        final String pkg = ExclusiveStepExecution.class.getPackageName();
        final Path dir = root.resolve(pkg.replace('.', '/'));
        final List<Class<?>> classes = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (final Path file : files.sorted().toList()) {
                final String name = file.getFileName().toString();
                if (name.endsWith(".class")) {
                    final String simple = name.substring(0, name.length() - ".class".length());
                    classes.add(Class.forName(pkg + '.' + simple, false, StateFixturesTest.class.getClassLoader()));
                }
            }
        }
        return classes;
    }

    private static ExclusiveStepExecution execution(final int version, final String name) throws Exception {
        return (ExclusiveStepExecution) deserialize(read(version, name));
    }

    private static URL fixture(final int version, final String name) {
        return StateFixturesTest.class.getResource("state/v" + version + '/' + name);
    }

    private static byte[] read(final int version, final String name) throws IOException {
        try (InputStream in = StateFixturesTest.class.getResourceAsStream("state/v" + version + '/' + name)) {
            assertNotNull(in, "No fixture state/v" + version + '/' + name + ". Run " + RECORD);
            return in.readAllBytes();
        }
    }

    private static byte[] serialize(final Object value) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        return bytes.toByteArray();
    }

    private static Object deserialize(final byte[] bytes) throws Exception {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return in.readObject();
        }
    }

    private static Object invokeStatic(final String type, final String method) throws Exception {
        return Class.forName(type).getMethod(method).invoke(null);
    }

    private static Object field(final Object target, final String name) throws Exception {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(final Object target, final String name, final Object value) throws Exception {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
