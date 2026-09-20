/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.init;

import io.github.green4j.piplex.Environment;
import io.github.green4j.piplex.Instances;
import io.github.green4j.piplex.exclusive.Designations;
import io.github.green4j.piplex.exclusive.ExclusiveRuns;
import io.github.green4j.piplex.init.Estate.Controller;
import io.github.green4j.piplex.init.Estate.Grants;
import io.github.green4j.piplex.init.Estate.Work;
import io.github.green4j.piplex.milestone.Milestones;
import io.github.green4j.piplex.switches.Switches;

import java.util.ArrayList;
import java.util.List;

/**
 * The cluster's ACL, composed from the keys the runtime actually writes.
 *
 * <p>Every prefix here comes from the same {@code keyOf} the steps call, so a grant and the key it
 * is meant to cover are one string rather than two that were compared by eye. That is the whole
 * reason this is generated: the ACL is the one artifact where a name typed slightly differently
 * fails closed at the moment somebody needed the operation.
 */
public final class Acl {

    /** Read, which every guard needs. */
    private static final String READ = ":G";
    /** Read and compare-and-set, which every write needs. */
    private static final String WRITE = ":GC";

    private Acl() {
    }

    /**
     * @param estate what was decided
     * @return the contents of a {@code --client-acl-file}
     */
    public static String render(final Estate estate) {
        final StringBuilder text = new StringBuilder();
        header(text, estate);
        for (final Controller controller : estate.controllers()) {
            line(text, controller.clientId(), grantsFor(estate, controller));
        }
        if (estate.operator().apart()) {
            line(text, estate.operator().clientId(), operatorGrants(estate));
        }
        return text.toString();
    }

    /**
     * @param estate     what was decided
     * @param controller the controller being granted
     * @return what that controller may do, as {@code <prefix>:<ops>} entries
     */
    static List<String> grantsFor(final Estate estate, final Controller controller) {
        final Environment in = estate.environment();
        final List<String> grants = new ArrayList<>();
        if (estate.grants() == Grants.PER_ENVIRONMENT) {
            grants.add(Environment.ROOT + in.value() + '/' + WRITE);
            active(grants, estate);
            return grants;
        }
        for (final Work work : estate.work()) {
            grants.add(ExclusiveRuns.keyOf(in, work.key()) + WRITE);
        }
        for (final Work work : estate.work()) {
            if (work.publishes()) {
                add(grants, Milestones.keyOf(in, work.completedWhen()) + WRITE);
            }
        }
        // The controller's own environment, not a step's: one machine, one mark, however many
        // environments its jobs name.
        grants.add(Instances.keyOf(in, controller.ownerId()) + WRITE);
        // Where the operator is separate it holds the C on designations and switches, and the
        // controller keeps G -- except its own drain key, which is why a controller can take itself
        // out unaided.
        final String whoWrites = estate.operator().apart() ? READ : WRITE;
        for (final Work work : estate.work()) {
            if (work.designated()) {
                add(grants, Designations.keyOf(in, work.designatedBy()) + whoWrites);
            }
        }
        for (final String key : estate.switchKeys()) {
            add(grants, Switches.keyOf(in, key) + whoWrites);
            if (estate.operator().apart()) {
                add(grants, Switches.keyOf(in, Switches.ownerKey(key, controller.ownerId())) + WRITE);
            }
        }
        active(grants, estate);
        return grants;
    }

    private static List<String> operatorGrants(final Estate estate) {
        final Environment in = estate.environment();
        final List<String> grants = new ArrayList<>();
        grants.add(in.prefixOf("designated") + WRITE);
        grants.add(in.prefixOf("enabled") + WRITE);
        return grants;
    }

    private static void active(final List<String> grants, final Estate estate) {
        for (final Work work : estate.work()) {
            if (work.follows()) {
                // Read only, and outside piplex's own root: piplex never writes it.
                add(grants, work.activeKey() + READ);
            }
        }
    }

    // Two pieces of work may share a switch, a designation or a milestone, and a grant repeated is
    // a longer line saying what the first one said.
    private static void add(final List<String> grants, final String grant) {
        if (!grants.contains(grant)) {
            grants.add(grant);
        }
    }

    private static void line(final StringBuilder text, final String clientId,
                             final List<String> grants) {
        text.append("acl.").append(clientId).append(" = ");
        for (int i = 0; i < grants.size(); i++) {
            if (i > 0) {
                text.append(" ; \\\n").append(" ".repeat(("acl." + clientId + " = ").length()));
            }
            text.append(grants.get(i));
        }
        text.append('\n');
    }

    private static void header(final StringBuilder text, final Estate estate) {
        text.append("# piplex ACL for '").append(estate.environment()).append("', generated by ")
                .append("piplex-init.\n#\n");
        text.append("# Every prefix below is the key the plugin writes, composed by the same code "
                + "that writes it.\n");
        text.append("# Regenerate this file rather than editing it: a key added here by hand is one "
                + "the jobs\n# and the pipeline do not know about.\n#\n");
        if (estate.grants() == Grants.PER_KEY) {
            text.append("# Granted key by key, so each controller may write only the work it runs. "
                    + "Every new work\n# key is an edit here and a POST /reload; a grant left out "
                    + "fails the work closed.\n");
        } else {
            text.append("# Granted per environment: one line each, no upkeep, and no separation "
                    + "within it.\n");
        }
        if (estate.operator().apart()) {
            text.append("#\n# Operator writes are made as '").append(estate.operator().clientId())
                    .append("'. Controllers read those two keys and\n"
                            + "# write only their own drain key.\n");
        }
        text.append("#\n# See docs/07-discas.md#acls\n\n");
    }
}
