/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.init;

import io.github.green4j.discas.common.cli.Prompt;
import io.github.green4j.piplex.Environment;
import io.github.green4j.piplex.init.Estate.Controller;
import io.github.green4j.piplex.init.Estate.Grants;
import io.github.green4j.piplex.init.Estate.Operator;
import io.github.green4j.piplex.init.Estate.Transport;
import io.github.green4j.piplex.init.Estate.Work;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The dialogue: a form with seven sections, in any order, as many times as it takes.
 *
 * <p>Not a wizard. A wizard is bearable the first time and insufferable the tenth, when the only
 * thing that changed is one switch key -- so this redraws the whole estate every round, marks what
 * is answered, and takes a number to jump straight at the section that is wrong. What it costs is
 * that nothing is validated at the end and everything is validated all the time: the problems the
 * estate has are printed under the menu, every round, until they are gone.
 *
 * <p>No terminal library, no cursor addressing, no raw keys. A numbered list and a typed number
 * work over ssh, in tmux, and in whatever a controller's console happens to be.
 */
public final class InitDialogue {

    private final Prompt prompt;
    private final Draft draft;

    /**
     * @param prompt where the questions are asked
     * @param start  the estate to begin from, usually one read back from a file
     */
    public InitDialogue(final Prompt prompt, final Estate start) {
        this.prompt = prompt;
        this.draft = new Draft(start);
    }

    /**
     * Runs the dialogue to the point where somebody asks for the estate to be written.
     *
     * @return the estate, or {@code null} where the dialogue was left without writing anything
     */
    public Estate run() {
        // Nobody is there to answer -- a pipe, a CI job, nohup -- so every question would return
        // its default and the menu would spin. The caller is told to name a description instead.
        if (!prompt.interactive()) {
            return draft.estate();
        }
        for (;;) {
            final Estate estate = draw();
            final String answer = prompt.ask(
                    "Section to change, [w] to write it out, [q] to leave without writing", "w")
                    .toLowerCase(Locale.ROOT);
            if ("q".equals(answer)) {
                return null;
            }
            if ("w".equals(answer)) {
                if (estate.problems().isEmpty()
                        || prompt.confirm("There are problems above. Write it anyway?", false)) {
                    return estate;
                }
                continue;
            }
            section(answer);
        }
    }

    private void section(final String answer) {
        switch (answer) {
            case "1" -> environment();
            case "2" -> cluster();
            case "3" -> transport();
            case "4" -> controllers();
            case "5" -> operator();
            case "6" -> work();
            case "7" -> grants();
            default -> prompt.say("  not one of 1..7, w or q");
        }
    }

    private Estate draw() {
        final Estate estate = draft.estate();
        prompt.heading("The estate");
        prompt.say("  1. Environment   " + draft.environment);
        prompt.say("  2. Cluster       " + summary(draft.nodes, "no nodes named"));
        prompt.say("  3. Transport     " + draft.transport.name().toLowerCase(Locale.ROOT)
                + (draft.transport == Transport.ALLOWALL ? "" : ", keys in " + draft.secretsDir));
        prompt.say("  4. Controllers   " + controllerSummary());
        prompt.say("  5. Operator      " + (draft.operatorClientId == null
                ? "acts as the controller" : "as '" + draft.operatorClientId + "'"));
        prompt.say("  6. Work          " + workSummary());
        prompt.say("  7. Grants        " + (draft.grants == Grants.PER_KEY
                ? "key by key" : "one line per environment"));
        for (final String problem : estate.problems()) {
            prompt.say("  ! " + problem);
        }
        return estate;
    }

    private String controllerSummary() {
        if (draft.controllers.isEmpty()) {
            return "none";
        }
        final List<String> names = new ArrayList<>();
        for (final Controller controller : draft.controllers) {
            names.add(controller.ownerId());
        }
        return String.join(", ", names);
    }

    private String workSummary() {
        if (draft.work.isEmpty()) {
            return "none";
        }
        final List<String> names = new ArrayList<>();
        for (final Work one : draft.work) {
            names.add(one.key() + (one.switched() ? "" : " (no switch)"));
        }
        return String.join(", ", names);
    }

    private void environment() {
        prompt.heading("Environment");
        prompt.say("A segment of every key piplex writes, so two sets of orchestrations can share "
                + "one cluster.");
        prompt.say("Changing it later moves every key, which is why it is asked first.");
        draft.environment = prompt.ask("Which set of orchestrations is this?", draft.environment);
    }

    private void cluster() {
        prompt.heading("Cluster");
        prompt.say("The discas client ports, as nodeId=host:port. Every controller uses the same "
                + "list.");
        prompt.say("IPv6 goes in brackets: n1=[2001:db8::1]:7101");
        draft.nodes = prompt.ask("The nodes", draft.nodes);
    }

    private void transport() {
        prompt.heading("Transport");
        prompt.say("How a controller reaches the cluster, which decides what a client id means "
                + "there.");
        draft.transport = prompt.choose("How is the client port protected?", Prompt.choices(
                Prompt.choice(Transport.ALLOWALL, "a trusted network",
                        "No token, no TLS. A client id is a claim, not a proof"),
                Prompt.choice(Transport.TOKEN, "a shared token over TLS",
                        "One secret for everybody who holds it"),
                Prompt.choice(Transport.MTLS, "client certificates",
                        "Each controller proves its own name; the CN is the client id")),
                draft.transport.ordinal());
        if (draft.transport == Transport.ALLOWALL) {
            return;
        }
        prompt.say("Nothing generated here holds a secret. This is where the files live on a "
                + "controller;");
        prompt.say("the pass phrases stay variables, and RUN.md says how to make the files if you "
                + "have none.");
        draft.secretsDir = prompt.ask("Where does the key material live?", draft.secretsDir);
    }

    private void controllers() {
        prompt.heading("Controllers");
        prompt.say("The owner id is what a designation names. The client id is what the cluster "
                + "authenticates");
        prompt.say("and the ACL grants -- and under mTLS it is the certificate's CN. Empty owner "
                + "id ends the list.");
        final List<Controller> named = new ArrayList<>();
        for (final Controller controller : draft.controllers) {
            final String ownerId = prompt.ask("Owner id (empty removes it)", controller.ownerId());
            if (ownerId.isBlank()) {
                continue;
            }
            named.add(new Controller(ownerId,
                    prompt.ask("  its client id", controller.clientId())));
        }
        for (;;) {
            final String ownerId = prompt.ask("Another owner id (empty to finish)", "");
            if (ownerId.isBlank()) {
                break;
            }
            named.add(new Controller(ownerId,
                    prompt.ask("  its client id", "piplex-" + ownerId)));
        }
        draft.controllers = named;
    }

    private void operator() {
        prompt.heading("Operator identity");
        prompt.say("Whether an operator write must be impossible under a controller's own identity "
                + "-- a");
        prompt.say("controller designating itself the owner, or draining its neighbour. Where the "
                + "controllers");
        prompt.say("belong to the team that would be doing the operating, that is not a threat "
                + "worth a");
        prompt.say("credential to distribute.");
        if (!prompt.confirm("Give the operator an identity of its own?",
                draft.operatorClientId != null)) {
            draft.operatorClientId = null;
            draft.operatorCredentialsId = null;
            return;
        }
        draft.operatorClientId = prompt.ask("Its client id",
                draft.operatorClientId == null ? "piplex-ops" : draft.operatorClientId);
        prompt.say("And the Jenkins credential carrying it, in the operator jobs' own folder -- "
                + "not the");
        prompt.say("global store, or every job on the controller can act as the operator.");
        draft.operatorCredentialsId = prompt.ask("The credential id",
                draft.operatorCredentialsId == null
                        ? draft.operatorClientId + "-token" : draft.operatorCredentialsId);
    }

    private void work() {
        prompt.heading("Work");
        prompt.say("Each piece of work and the keys it names. These four travel together: the "
                + "operator jobs");
        prompt.say("read them as a set, so a guard belonging to another piece of work reports on "
                + "the wrong thing.");
        final List<Work> named = new ArrayList<>();
        for (final Work one : draft.work) {
            final String key = prompt.ask("Work key (empty removes it)", one.key());
            if (key.isBlank()) {
                continue;
            }
            named.add(asked(key, one));
        }
        for (;;) {
            final String key = prompt.ask("Another work key (empty to finish)", "");
            if (key.isBlank()) {
                break;
            }
            named.add(asked(key, Work.of(key, key + "-switch", key + "-owner", null, null)));
        }
        draft.work = named;
    }

    private Work asked(final String key, final Work from) {
        prompt.say("  The switch is what the stop job and the drain both write. Work that names "
                + "none");
        prompt.say("  cannot be stopped at all, and a drain reports the controller quiet while it "
                + "runs on.");
        final String enabledBy = prompt.ask("  its switch, in enabledBy", or(from.enabledBy(), ""));
        prompt.say("  Who runs it: piplex's own designation, or a key somebody else writes. Not "
                + "both --");
        prompt.say("  they answer the same question, and a request carrying both is refused.");
        final boolean designated = prompt.confirm("  Does piplex decide where it runs?",
                !from.follows());
        final String designatedBy = designated
                ? prompt.ask("  its designation key", or(from.designatedBy(), key + "-owner")) : "";
        final String activeKey = designated
                ? "" : prompt.ask("  the key that says who runs", or(from.activeKey(), "/dc/active"));
        final String completedWhen = prompt.ask(
                "  the milestone it publishes, if any", or(from.completedWhen(), ""));
        final String handoverWait = prompt.ask(
                "  how long a standby parks waiting to take over, if at all",
                or(from.handoverWait(), ""));
        return new Work(key, blankToNull(enabledBy), blankToNull(designatedBy),
                blankToNull(activeKey), blankToNull(completedWhen),
                from.lease(), blankToNull(handoverWait));
    }

    private void grants() {
        prompt.heading("Grants");
        prompt.say("How narrowly each identity is granted. Grants add rather than narrow: a longer "
                + "prefix");
        prompt.say("does not override a shorter one, so a blanket grant cannot be taken back by a "
                + "narrow one.");
        draft.grants = prompt.choose("How is the ACL written?", Prompt.choices(
                Prompt.choice(Grants.PER_ENVIRONMENT, "a grant per environment",
                        "One line each, no upkeep, and no separation within it"),
                Prompt.choice(Grants.PER_KEY, "key by key",
                        "A controller may write only the work it runs. Every new key is an edit")),
                draft.grants.ordinal());
    }

    private static String summary(final String value, final String whenEmpty) {
        return value == null || value.isBlank() ? whenEmpty : value;
    }

    private static String or(final String value, final String fallback) {
        return value == null ? fallback : value;
    }

    private static String blankToNull(final String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** The answers so far, which is the only thing here that is allowed to be half-finished. */
    private static final class Draft {

        private String environment;
        private String nodes;
        private Transport transport;
        private String secretsDir;
        private String operatorClientId;
        private String operatorCredentialsId;
        private List<Controller> controllers;
        private List<Work> work;
        private Grants grants;

        private Draft(final Estate start) {
            environment = start.environment().value();
            nodes = start.nodes();
            transport = start.transport();
            secretsDir = start.secretsDir();
            operatorClientId = start.operator().clientId();
            operatorCredentialsId = start.operator().credentialsId();
            controllers = new ArrayList<>(start.controllers());
            work = new ArrayList<>(start.work());
            grants = start.grants();
        }

        private Estate estate() {
            return new Estate(Environment.of(environment), List.copyOf(controllers), nodes,
                    transport, secretsDir,
                    new Operator(operatorClientId, operatorCredentialsId),
                    List.copyOf(work), grants);
        }
    }
}
