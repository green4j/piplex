/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.init;

import io.github.green4j.discas.common.cli.GetOpts;
import io.github.green4j.discas.common.cli.Prompt;
import io.github.green4j.piplex.Environment;
import io.github.green4j.piplex.init.Estate.Grants;
import io.github.green4j.piplex.init.Estate.Operator;
import io.github.green4j.piplex.init.Estate.Transport;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

/**
 * Prepares a piplex deployment: the ACL, the controller configurations, the operator jobs and the
 * block each guarded pipeline needs, from one description of the estate.
 *
 * <p>These four have to name the same keys. Written by hand they are four places to keep in step,
 * and the way they stop being in step is silent -- a grant fails a write that nobody makes until
 * the night somebody needs it. Written together from one description, they cannot drift.
 *
 * <pre>{@code
 * piplex-init                                 # ask, then write into ./piplex
 * piplex-init --from piplex/estate.properties # ask again, starting from what was decided
 * piplex-init --from piplex/estate.properties --quiet   # regenerate without asking
 * }</pre>
 */
public final class PiplexInit {

    private static final GetOpts OPTS = new GetOpts("piplex-init",
            "Prepares a piplex deployment: the cluster ACL, each controller's configuration, the "
            + "operator jobs and the block each guarded pipeline needs -- all from one description "
            + "of the estate, so they name the same keys by construction.")
            .stringOpt("from", 'f', null,
                    "An estate description to start from, as written by an earlier run.")
            .stringOpt("out", 'o', "piplex",
                    "Generator-owned directory to replace as one complete tree.")
            .flag("quiet", 'q',
                    "Ask nothing: regenerate from --from as it stands, or exit 2 if it is invalid.")
            .epilogue("With no console -- a pipe, a CI job, nohup -- nothing is asked and --from is "
                    + "required, because an estate nobody described is an estate of no "
                    + "controllers.");

    private PiplexInit() {
    }

    /**
     * @param args the command line
     * @throws IOException if the description cannot be read or the output cannot be written
     */
    public static void main(final String[] args) throws IOException {
        OPTS.parse(args, false);
        final Prompt prompt = Prompt.console();
        final String from = OPTS.getString("from");
        final boolean asking = prompt.interactive() && !OPTS.getBool("quiet");

        if (from == null && !asking) {
            System.err.println("piplex-init: nobody is there to answer and no --from was given. "
                    + "Name a description to generate from.");
            System.exit(2);
            return;
        }
        final Estate start = from == null ? empty() : Estates.read(Path.of(from));
        final Estate estate = asking ? new InitDialogue(prompt, start).run() : start;
        if (estate == null) {
            System.out.println("Nothing written.");
            return;
        }
        final int status = write(estate, Path.of(OPTS.getString("out")), System.out, System.err);
        if (status != 0) {
            System.exit(status);
        }
    }

    static int write(final Estate estate, final Path into,
                     final PrintStream out, final PrintStream err) throws IOException {
        final List<String> problems = estate.problems();
        if (!problems.isEmpty()) {
            err.println();
            for (final String problem : problems) {
                err.println("  ! " + problem);
            }
            err.println();
            err.println("Nothing written.");
            return 2;
        }

        final List<Path> written = Output.write(estate, into);
        out.println();
        for (final Path file : written) {
            out.println("  " + file);
        }
        out.println();
        out.println("Read " + into.resolve(RunNotes.FILE)
                + " next: it says which file goes where.");
        return 0;
    }

    // Not a sample estate. Every name that decides a key is absent, so a run that answers nothing
    // produces an estate that says what is missing rather than one pointed at somebody's cluster.
    private static Estate empty() {
        return new Estate(Environment.of("default"), List.of(), "", Transport.ALLOWALL,
                Estate.SECRETS, Operator.SHARED, List.of(), Grants.PER_ENVIRONMENT);
    }
}
