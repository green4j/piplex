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
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * The designated owner and human-readable provenance of its latest change.
 *
 * <p>Only {@code owner} controls admission. Other fields are parsed leniently and do not form an audit
 * trail.
 *
 * @param owner  who may run the work
 * @param reason why, may be {@code null}
 * @param at     when, may be {@code null}
 * @param prev   who held it before, may be {@code null}
 * @param seq    how many times this has changed
 */
public record Designation(String owner, String reason, Instant at, String prev, long seq) {

    /**
     * @param owner  who may run the work
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
            throw new IllegalArgumentException("Not a JSON object: " + text, notAnObject);
        }
        final String at = object.getString("at");
        return new Designation(
                object.getStringRequired("owner"),
                object.getString("reason"),
                instantOrNull(at),
                object.getString("prev"),
                object.getLong("seq"));
    }

    // Provenance: a stamp that does not parse is dropped rather than making the whole key unreadable.
    private static Instant instantOrNull(final String text) {
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (final DateTimeParseException malformed) {
            return null;
        }
    }

    /**
     * The record which replaces this one.
     *
     * @param newOwner who may run the work from now on
     * @param why      the reason, ideally a ticket
     * @param when     now
     * @return the next record, with the counter moved on and this owner recorded as the previous one
     */
    public Designation succeededBy(final String newOwner, final String why, final Instant when) {
        return new Designation(newOwner, why, when, owner, seq + 1L);
    }

    /**
     * @return the JSON to hold at the key
     */
    public String toJson() {
        final JsonValue value = JsonValue.newObject();
        final JsonObject object = value.asObject();
        object.putString("owner", owner);
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
