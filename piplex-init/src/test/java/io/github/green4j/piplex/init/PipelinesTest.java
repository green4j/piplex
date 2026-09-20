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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The block a pipeline gets, against the ACL the cluster gets.
 *
 * <p>Whether the block runs is asked where it can be answered -- against a real Jenkins, in
 * {@code GeneratedEstateTest}. What is asked here is the thing generating both halves was for: that
 * the keys the work consults are the keys the controller is granted.
 */
class PipelinesTest {

    private static final Controller BLUE = new Controller("euc1-blue", "piplex-euc1-blue");
    private static final Controller GREEN = new Controller("euc1-green", "piplex-euc1-green");

    @Test
    void namesOnlyKeysTheAclGrants() {
        final Estate estate = estate(Work.of("eod", "nightly", "eod-owner", null, "data/euc1"));

        final String block = Pipelines.render(estate.work().get(0), BLUE);
        final String acl = Acl.render(estate);

        for (final String key : List.of("eod", "nightly", "eod-owner", "data/euc1")) {
            assertTrue(block.contains("'" + key + "'"), block);
            assertTrue(acl.contains('/' + key + ':'),
                    "The work consults '" + key + "' and the ACL does not grant it:\n" + acl);
        }
    }

    // A designation and an active key are two answers to who runs, and the request is refused when
    // it carries both. The one the estate names is the one that appears.
    @Test
    void writesTheAnswerToWhoRunsExactlyOnce() {
        final String designated = Pipelines.render(
                Work.of("eod", "nightly", "eod-owner", null, null), BLUE);
        assertTrue(designated.contains("designatedBy: 'eod-owner'"), designated);
        assertFalse(designated.contains("activeWhenKey"), designated);

        final String followed = Pipelines.render(
                Work.of("eod", "nightly", null, "/dc/active", null), GREEN);
        assertFalse(followed.contains("designatedBy"), followed);
        assertTrue(followed.contains("activeWhenKey: '/dc/active'"), followed);
        assertTrue(followed.contains("activeWhenValue: 'euc1-green'"),
                "The value admitting this controller is the one thing in the block that differs "
                        + "per controller:\n" + followed);
    }

    @Test
    void leavesOutEveryGuardTheWorkDoesNotName() {
        final String bare = Pipelines.render(Work.of("eod", null, "eod-owner", null, null), BLUE);

        assertFalse(bare.contains("enabledBy"), bare);
        assertFalse(bare.contains("completedWhen"), bare);
        assertFalse(bare.contains("piplexPublish"),
                "Nothing is published where nothing was named to publish:\n" + bare);
        assertFalse(bare.contains("lease"), bare);
        assertFalse(bare.contains("handoverWait"), bare);
    }

    // A milestone published after the lease goes back is published by a build that no longer holds
    // the work, so the block carries the post step rather than leaving it to be remembered.
    @Test
    void carriesThePublicationWithTheGuardThatMakesItSafe() {
        final String block = Pipelines.render(
                Work.of("eod", "nightly", "eod-owner", null, "data/euc1"), BLUE);

        assertTrue(block.contains("completedWhen: 'data/euc1'"), block);
        assertTrue(block.contains("piplexPublish key: 'data/euc1'"), block);
        assertTrue(block.indexOf("options {") < block.indexOf("post {"), block);
    }

    private static Estate estate(final Work... work) {
        return new Estate(Environment.of("prod"), List.of(BLUE, GREEN), "n1=10.0.0.11:7101",
                Transport.ALLOWALL, Estate.SECRETS, Operator.SHARED, List.of(work), Grants.PER_KEY);
    }
}
