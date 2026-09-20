/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.init;

import io.github.green4j.piplex.Environment;
import io.github.green4j.piplex.init.Estate.Controller;
import io.github.green4j.piplex.init.Estate.Grants;
import io.github.green4j.piplex.init.Estate.Operator;
import io.github.green4j.piplex.init.Estate.Transport;
import io.github.green4j.piplex.init.Estate.Work;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * The estate as a file, so the second run of this tool is not the first one again.
 *
 * <p>Answering twenty questions is bearable once. What makes it bearable the tenth time -- a new
 * controller, a new piece of work, a switch renamed -- is that the answers are kept, and the
 * dialogue starts from them. This is also the only artifact here anybody edits by hand: everything
 * else is generated from it and says so.
 */
public final class Estates {

    /** What the dialogue writes and reads back. */
    public static final String FILE = "estate.properties";

    private Estates() {
    }

    /**
     * @param file the description to read
     * @return the estate it describes
     * @throws IOException if the file cannot be read
     */
    public static Estate read(final Path file) throws IOException {
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            final Properties properties = new Properties();
            properties.load(in);
            return of(properties);
        }
    }

    /**
     * @param properties the description
     * @return the estate it describes
     */
    public static Estate of(final Properties properties) {
        final List<Controller> controllers = new ArrayList<>();
        for (final String ownerId : listed(properties, "controllers")) {
            controllers.add(new Controller(ownerId,
                    value(properties, "controller." + ownerId + ".clientId", "piplex-" + ownerId)));
        }
        final List<Work> work = new ArrayList<>();
        for (final String key : listed(properties, "work")) {
            work.add(new Work(key,
                    value(properties, "work." + key + ".enabledBy", null),
                    value(properties, "work." + key + ".designatedBy", null),
                    value(properties, "work." + key + ".activeKey", null),
                    value(properties, "work." + key + ".completedWhen", null),
                    value(properties, "work." + key + ".lease", null),
                    value(properties, "work." + key + ".handoverWait", null)));
        }
        return new Estate(
                Environment.of(value(properties, "environment", "default")),
                List.copyOf(controllers),
                value(properties, "nodes", ""),
                enumerated(Transport.class, value(properties, "transport", "allowall")),
                value(properties, "secretsDir", Estate.SECRETS),
                new Operator(value(properties, "operator.clientId", null),
                        value(properties, "operator.credentialsId", null)),
                List.copyOf(work),
                enumerated(Grants.class, value(properties, "grants", "per-environment")));
    }

    /**
     * @param estate what was decided
     * @return the description, ready to be read back by {@link #read(Path)}
     */
    public static String render(final Estate estate) {
        final StringBuilder text = new StringBuilder();
        text.append("# The estate, as piplex-init last read it. Everything else in this directory\n"
                + "# is generated from this file -- edit it and run the tool again rather than\n"
                + "# editing what it wrote.\n\n");
        field(text, "environment", estate.environment().value());
        field(text, "nodes", estate.nodes());
        field(text, "transport", name(estate.transport()));
        field(text, "secretsDir", estate.secretsDir());
        field(text, "grants", name(estate.grants()));
        text.append('\n');
        text.append("# Empty where operator writes are made as the controller itself.\n");
        field(text, "operator.clientId", estate.operator().clientId());
        field(text, "operator.credentialsId", estate.operator().credentialsId());
        text.append("\n# The controllers competing for the work. The owner id is what a designation\n"
                + "# names; the client id is what the cluster authenticates and the ACL grants.\n");
        field(text, "controllers", joined(estate.controllers().stream()
                .map(Controller::ownerId).toList()));
        for (final Controller controller : estate.controllers()) {
            field(text, "controller." + controller.ownerId() + ".clientId", controller.clientId());
        }
        text.append("\n# Every piece of work, and the keys each one names. The four guards of one\n"
                + "# piece of work travel together: the operator jobs read them as a set.\n");
        field(text, "work", joined(estate.work().stream().map(Work::key).toList()));
        for (final Work work : estate.work()) {
            text.append('\n');
            field(text, "work." + work.key() + ".enabledBy", work.enabledBy());
            field(text, "work." + work.key() + ".designatedBy", work.designatedBy());
            field(text, "work." + work.key() + ".activeKey", work.activeKey());
            field(text, "work." + work.key() + ".completedWhen", work.completedWhen());
            field(text, "work." + work.key() + ".lease", work.lease());
            field(text, "work." + work.key() + ".handoverWait", work.handoverWait());
        }
        return text.toString();
    }

    private static void field(final StringBuilder text, final String name, final String value) {
        text.append(name).append(" = ").append(value == null ? "" : value).append('\n');
    }

    private static String joined(final List<String> values) {
        return String.join(", ", values);
    }

    private static List<String> listed(final Properties properties, final String name) {
        final String value = value(properties, name, "");
        if (value == null || value.isBlank()) {
            return List.of();
        }
        final List<String> values = new ArrayList<>();
        for (final String one : value.split(",")) {
            if (!one.isBlank()) {
                values.add(one.strip());
            }
        }
        return values;
    }

    private static String value(final Properties properties, final String name,
                                final String fallback) {
        final String value = properties.getProperty(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.strip();
    }

    // 'per-key' in a file somebody types, PER_KEY in the code. Nobody should have to know which.
    private static <T extends Enum<T>> T enumerated(final Class<T> type, final String value) {
        final String wanted = value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        for (final T one : type.getEnumConstants()) {
            if (one.name().equals(wanted)) {
                return one;
            }
        }
        final List<String> known = new ArrayList<>();
        for (final T one : type.getEnumConstants()) {
            known.add(name(one));
        }
        throw new IllegalArgumentException("'" + value + "' is not one of " + known);
    }

    private static String name(final Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
