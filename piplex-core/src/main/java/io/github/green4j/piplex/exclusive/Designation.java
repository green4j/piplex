/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.exclusive;

import io.github.green4j.jelly.simple.JsonObject;
import io.github.green4j.jelly.simple.JsonValue;
import io.github.green4j.jelly.simple.JsonValueParser;

import java.io.StringWriter;
import java.time.Instant;
import java.util.Objects;

/**
 * Who is currently allowed to run the work, and the trail of how that came to be.
 *
 * <p>Only {@code owner} is acted on. The rest is provenance, and it is here for two reasons.
 *
 * <p>The first is that a key which explains itself saves somebody a trip to another system at three in
 * the morning. The second is {@code seq}, which says how many times this has changed -- useful to a
 * person comparing two observations, and free to keep.
 *
 * <p>Nothing here is read by piplex except {@code owner}, and in particular nothing reconstructs the
 * sequence of changes from it. Everything that waits on this key compares the state it finds, so a
 * change that went by unobserved costs nothing: if the owner flipped away and back, the coalesced
 * answer is the right one and acting on the intermediate would have been the mistake.
 *
 * <p>None of this is an audit trail. It is written by the same client it describes, it holds only the
 * latest change, and a wrong or malicious writer can put anything in it. Audit belongs at the operation,
 * not in the value.
 *
 * @param owner  who may run the work
 * @param by     who changed it, may be {@code null}
 * @param reason why, may be {@code null}
 * @param at     when, may be {@code null}
 * @param prev   who held it before, may be {@code null}
 * @param seq    how many times this has changed
 */
public record Designation(String owner, String by, String reason, Instant at, String prev, long seq) {

    /**
     * @param owner  who may run the work
     * @param by     who changed it
     * @param reason why
     * @param at     when
     * @param prev   who held it before
     * @param seq    how many times this has changed
     */
    public Designation {
        Objects.requireNonNull(owner, "owner");
    }

    /**
     * Reads a record.
     *
     * @param text the JSON held at the key
     * @return the record
     */
    public static Designation parse(final String text) {
        final JsonObject object;
        try {
            object = new JsonValueParser().parseAndEoj(text).asObjectRequired();
        } catch (final RuntimeException notAnObject) {
            throw new IllegalArgumentException("not a JSON object: " + text, notAnObject);
        }
        final String at = object.getString("at");
        return new Designation(
                object.getStringRequired("owner"),
                object.getString("by"),
                object.getString("reason"),
                at == null ? null : Instant.parse(at),
                object.getString("prev"),
                object.getLong("seq"));
    }

    /**
     * The record which replaces this one.
     *
     * @param newOwner who may run the work from now on
     * @param changedBy who is changing it
     * @param why      the reason, ideally a ticket
     * @param when     now
     * @return the next record, with the counter moved on and this owner recorded as the previous one
     */
    public Designation succeededBy(final String newOwner,
                                   final String changedBy,
                                   final String why,
                                   final Instant when) {
        return new Designation(newOwner, changedBy, why, when, owner, seq + 1L);
    }

    /**
     * @return the JSON to hold at the key
     */
    public String toJson() {
        final JsonValue value = JsonValue.newObject();
        final JsonObject object = value.asObject();
        object.putString("owner", owner);
        if (by != null) {
            object.putString("by", by);
        }
        if (reason != null) {
            object.putString("reason", reason);
        }
        if (at != null) {
            object.putString("at", at.toString());
        }
        if (prev != null) {
            object.putString("prev", prev);
        }
        object.putLong("seq", seq);
        final StringWriter json = new StringWriter();
        value.toJsonAndEoj(json);
        return json.toString();
    }
}
