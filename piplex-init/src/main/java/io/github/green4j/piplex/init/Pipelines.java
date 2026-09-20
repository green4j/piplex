/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.init;

import io.github.green4j.piplex.init.Estate.Controller;
import io.github.green4j.piplex.init.Estate.Work;

/**
 * The {@code piplexExclusive} block one piece of work puts in its pipeline.
 *
 * <p>This is the half of the estate that is not piplex's to install. The pipeline belongs to
 * whoever wrote it -- generated from a template, held in a repository, reviewed by people who have
 * never heard of piplex -- so what is produced here is a block to paste into it, not a file to
 * overwrite.
 *
 * <p>It is generated all the same, because the guards in it and the keys in the ACL have to be the
 * same names. They are composed from one description here, which is the only arrangement where a
 * switch the operator can write and a switch the work consults cannot drift apart.
 */
public final class Pipelines {

    private Pipelines() {
    }

    /**
     * @param work       the work being guarded
     * @param controller the controller this copy of the pipeline runs on, which matters only where
     *                   an external key decides who runs
     * @return the {@code options} block, indented for a declarative pipeline
     */
    public static String render(final Work work, final Controller controller) {
        final StringBuilder text = new StringBuilder();
        text.append("    // Guards the whole build, including post.\n");
        text.append("    options {\n        piplexExclusive(\n");
        field(text, "key", work.key(),
                "The work. One build at a time across every controller");
        if (work.designated()) {
            field(text, "designatedBy", work.designatedBy(),
                    "Who runs it. Written by the handover job, read here");
        }
        if (work.follows()) {
            field(text, "activeWhenKey", work.activeKey(),
                    "Who runs it, decided outside piplex. Piplex only reads this");
            field(text, "activeWhenValue", controller.ownerId(),
                    "The value that admits THIS controller. It differs per controller");
        }
        if (work.switched()) {
            field(text, "enabledBy", work.enabledBy(),
                    "The switch. Off revokes runs in flight and skips new ones");
        }
        if (work.publishes()) {
            raw(text, "generation", "params.BUSINESS_DATE",
                    "What this round produces. Replace with your own convention");
            field(text, "completedWhen", work.completedWhen(),
                    "Already produced? Then this build ends NOT_BUILT");
        }
        if (named(work.lease())) {
            field(text, "lease", work.lease(),
                    "Ownership term, renewed while the build runs");
        }
        if (named(work.handoverWait())) {
            field(text, "handoverWait", work.handoverWait(),
                    "How long a candidate parks, holding no executor");
        }
        trim(text);
        text.append("\n        )\n    }\n");
        if (work.publishes()) {
            text.append('\n').append(post(work));
        }
        return text.toString();
    }

    // The milestone goes inside the guarded body, and in a declarative pipeline the options wrapper
    // is the body: post runs while the lease is still held. A scripted pipeline has to put this
    // inside the piplexExclusive { ... } block itself, or it publishes after letting go.
    private static String post(final Work work) {
        return "    post {\n        success {\n"
                + "            // Records the round. Waiting consumers continue; later builds for\n"
                + "            // the same one skip. Runs before the lease is released.\n"
                + "            piplexPublish key: '" + escaped(work.completedWhen()) + "',\n"
                + "                          generation: params.BUSINESS_DATE\n"
                + "        }\n    }\n";
    }

    private static void field(final StringBuilder text, final String name, final String value,
                              final String why) {
        raw(text, name, "'" + escaped(value) + "'", why);
    }

    private static void raw(final StringBuilder text, final String name, final String value,
                            final String why) {
        text.append("            // ").append(why).append('\n');
        text.append("            ").append(name).append(": ").append(value).append(",\n");
    }

    private static void trim(final StringBuilder text) {
        text.setLength(text.length() - 2);
    }

    private static String escaped(final String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static boolean named(final String value) {
        return value != null && !value.isBlank();
    }
}
