/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.init;

import io.github.green4j.piplex.init.Estate.Controller;
import io.github.green4j.piplex.init.Estate.Work;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The operator jobs, filled in for one controller.
 *
 * <p>Each job ships as a template carrying the whole procedure and an empty estate, and this
 * replaces the empty estate. The procedure is not generated and not edited: it is what the tests
 * run, so a job that stops working is noticed here rather than during an incident.
 *
 * <p>The split is deliberate. What a piece of work is called, which switch it consults and which
 * milestone it publishes are facts about the estate, settled once; which of them an operator is
 * acting on tonight is a question for the person pressing Build. The first goes in the definition,
 * the second stays a parameter -- and the four guards of one piece of work travel together, so
 * choosing a work names all four rather than inviting somebody to mix two.
 */
public final class Jobs {

    /** The jobs that ship, in the order the deployment chapter introduces them. */
    public static final List<String> ALL = List.of("inspect.groovy", "handover.groovy",
            "drain-controller.groovy", "stop-work.groovy", "announce-completion.groovy");

    /** Draining is done by the controller itself, so its job never carries the operations one. */
    private static final String SELF_DRAIN = "drain-controller.groovy";

    private static final String OPENS =
            "// ---- piplex-init: generated from the estate. Regenerate rather than editing it. ----";
    private static final String CLOSES = "// ---- end piplex-init ----";

    private Jobs() {
    }

    /**
     * @param estate     what was decided
     * @param controller the controller this copy of the job is installed on
     * @param job        one of {@link #ALL}
     * @return the job, ready to install
     */
    public static String render(final Estate estate, final Controller controller, final String job) {
        final String template = template(job);
        final int opens = template.indexOf(OPENS);
        final int closes = template.indexOf(CLOSES);
        if (opens < 0 || closes < opens) {
            throw new IllegalStateException("'" + job + "' has no piplex-init block to fill in");
        }
        return template.substring(0, opens + OPENS.length())
                + '\n' + estateOf(estate, controller, job)
                + template.substring(closes);
    }

    private static String estateOf(final Estate estate, final Controller controller,
                                   final String job) {
        // Draining writes the controller's own key under the controller's own identity. Handing it
        // the operations credential would put that credential on every controller and undo the
        // separation it was created for.
        final boolean operates = estate.operator().apart() && !SELF_DRAIN.equals(job);
        final StringBuilder text = new StringBuilder();
        text.append("final String ENVIRONMENT = ").append(quoted(estate.environment().value()))
                .append('\n');
        text.append("final String OWNER_ID = ").append(quoted(controller.ownerId())).append('\n');
        text.append("final List OWNERS = ").append(list(owners(estate))).append('\n');
        text.append("final String CREDENTIALS_ID = ")
                .append(quoted(operates ? estate.operator().credentialsId() : "")).append('\n');
        text.append("final String CLIENT_ID = ")
                .append(quoted(operates ? estate.operator().clientId() : "")).append('\n');
        work(text, estate);
        return text.toString();
    }

    private static void work(final StringBuilder text, final Estate estate) {
        text.append("final Map WORK = [\n");
        for (final Work work : estate.work()) {
            text.append("        ").append(quoted(work.key())).append(": [")
                    .append("key: ").append(quoted(work.key()))
                    .append(", enabledBy: ").append(quoted(work.enabledBy()))
                    .append(",\n                ")
                    .append("designatedBy: ").append(quoted(work.designatedBy()))
                    .append(", activeKey: ").append(quoted(work.activeKey()))
                    .append(",\n                ")
                    .append("completedWhen: ").append(quoted(work.completedWhen()))
                    .append("],\n");
        }
        text.append("]\n");
    }

    private static List<String> owners(final Estate estate) {
        return estate.controllers().stream().map(Controller::ownerId).toList();
    }

    private static String list(final List<String> values) {
        final StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(quoted(values.get(i)));
        }
        return text.append(']').toString();
    }

    // A name reaches here from a properties file somebody typed, and lands in code that is executed.
    // Nothing else in this file escapes anything, because nothing else takes a value from outside.
    private static String quoted(final String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /**
     * @param job one of {@link #ALL}
     * @return the template as it ships, with its estate block empty
     */
    public static String template(final String job) {
        try (InputStream in = Jobs.class.getResourceAsStream("/jobs/" + job)) {
            if (in == null) {
                throw new IllegalArgumentException("There is no job template called '" + job + "'");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException failed) {
            throw new UncheckedIOException("Could not read the template of '" + job + "'", failed);
        }
    }
}
