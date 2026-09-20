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
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dialogue, answered.
 *
 * <p>Driven through {@link Prompt}'s own reader and stream rather than a stand-in, so what is being
 * tested is the questions as an operator meets them: the order, the defaults, and what an empty
 * answer means. The scripts below read as a session, which is also how they are checked -- an
 * answer landing on the wrong question shifts everything after it.
 */
class InitDialogueTest {

    // Somebody who knows what they want, typing quickly. An empty answer takes the default, so the
    // shortest true session is the one that says what is different about this estate and nothing
    // else. The default for the menu is 'w', so the last empty line writes it out.
    @Test
    void takesAnEstateFromNothing() {
        final Said said = answer(
                "1", "prod",
                "2", "n1=10.0.0.11:7101,n2=10.0.0.12:7101",
                "4", "euc1-blue", "", "euc1-green", "", "",
                "6", "eod", "nightly", "y", "eod-owner", "data/euc1", "4h", "",
                "7", "2",
                "");

        final Estate estate = said.estate;
        assertEquals("prod", estate.environment().value());
        assertEquals(List.of(new Controller("euc1-blue", "piplex-euc1-blue"),
                new Controller("euc1-green", "piplex-euc1-green")), estate.controllers());
        assertEquals(List.of(new Work("eod", "nightly", "eod-owner", null, "data/euc1", null, "4h")),
                estate.work());
        assertEquals(Grants.PER_KEY, estate.grants());
        assertEquals(List.of(), estate.problems(), "And it is an estate that can be installed");
    }

    // The tenth run, not the first: one switch key is wrong and nothing else is. Jumping straight
    // at the section that is wrong is the whole reason this is a menu rather than a wizard.
    @Test
    void changesOneAnswerWithoutAskingTheOthersAgain() {
        final Said said = from(complete(),
                "6", "eod", "corrected-switch", "y", "eod-owner", "data/euc1", "4h", "",
                "");

        assertEquals("corrected-switch", said.estate.work().get(0).enabledBy());
        assertEquals("prod", said.estate.environment().value(), "Untouched, and not asked again");
        assertEquals(2, said.estate.controllers().size());
    }

    @Test
    void leavesWithoutWritingWhenAskedTo() {
        assertNull(from(complete(), "q").estate, "Nothing is written on the way out");
    }

    // The problems are not a final verdict, they are on the screen the whole time -- so somebody
    // fixing one can see the rest, and somebody who meant it can still write it out.
    @Test
    void showsWhatIsWrongEveryRoundAndStillTakesAnOrder() {
        final Said said = from(new Estate(Environment.of("prod"), List.of(), "",
                        Transport.ALLOWALL, Estate.SECRETS, Operator.SHARED, List.of(),
                        Grants.PER_ENVIRONMENT),
                "w", "y");

        assertTrue(said.printed.contains("! No controllers are named"), said.printed);
        assertTrue(said.printed.contains("! No work is named"), said.printed);
        assertTrue(said.printed.contains("There are problems above"), said.printed);
        assertTrue(said.estate.problems().size() >= 3, "And it wrote it anyway, having asked");
    }

    // Nobody is there: a pipe, a CI job, nohup. Every question would return its default, so the
    // menu would spin for ever. It returns what it was given instead, and main() refuses to run
    // without a description in that case.
    @Test
    void asksNothingWhereNobodyIsThereToAnswer() {
        final ByteArrayOutputStream printed = new ByteArrayOutputStream();
        final Prompt silent = new Prompt(new BufferedReader(new StringReader("")),
                new PrintStream(printed, true, StandardCharsets.UTF_8), false, false);

        assertEquals(complete(), new InitDialogue(silent, complete()).run());
        assertEquals("", printed.toString(StandardCharsets.UTF_8));
    }

    private static Said answer(final String... typed) {
        return from(empty(), typed);
    }

    // One element per line typed, so an empty answer -- which is how a default is taken -- is an
    // empty string rather than a blank line nobody can see.
    private static Said from(final Estate start, final String... typed) {
        final ByteArrayOutputStream printed = new ByteArrayOutputStream();
        final Prompt prompt = new Prompt(
                new BufferedReader(new StringReader(String.join("\n", typed) + '\n')),
                new PrintStream(printed, true, StandardCharsets.UTF_8), true, false);
        final Estate estate = new InitDialogue(prompt, start).run();
        return new Said(estate, printed.toString(StandardCharsets.UTF_8));
    }

    private record Said(Estate estate, String printed) {
    }

    private static Estate empty() {
        return new Estate(Environment.of("default"), List.of(), "", Transport.ALLOWALL,
                Estate.SECRETS, Operator.SHARED, List.of(), Grants.PER_ENVIRONMENT);
    }

    private static Estate complete() {
        return new Estate(Environment.of("prod"),
                List.of(new Controller("euc1-blue", "piplex-euc1-blue"),
                        new Controller("euc1-green", "piplex-euc1-green")),
                "n1=10.0.0.11:7101", Transport.ALLOWALL, Estate.SECRETS, Operator.SHARED,
                List.of(Work.of("eod", "eod-switch", "eod-owner", null, "data/euc1")),
                Grants.PER_KEY);
    }
}
