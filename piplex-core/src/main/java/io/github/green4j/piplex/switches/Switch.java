/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.switches;

import io.github.green4j.jelly.simple.JsonObject;
import io.github.green4j.jelly.simple.JsonValue;
import io.github.green4j.jelly.simple.JsonValueParser;

import java.io.StringWriter;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Whether work is enabled, with provenance for the latest switch change.
 *
 * <p>Only {@code enabled} controls admission. {@code reason} and {@code at} explain the current state.
 *
 * @param enabled whether the work may run
 * @param reason  why it was switched off, may be {@code null}
 * @param at      when it was switched, may be {@code null}
 */
public record Switch(boolean enabled, String reason, Instant at) {

    /** What an absent key means: on. Switching off is the deliberate act, never the default. */
    public static final Switch ENABLED = new Switch(true, null, null);

    /**
     * Reads a record.
     *
     * @param text the JSON held at the key
     * @return the record
     */
    public static Switch parse(final String text) {
        final JsonObject object;
        try {
            object = new JsonValueParser().parseAndEoj(text).asObjectRequired();
        } catch (final RuntimeException notAnObject) {
            throw new IllegalArgumentException("Not a JSON object: " + text, notAnObject);
        }
        final String at = object.getString("at");
        return new Switch(object.getBooleanRequired("enabled"), object.getString("reason"),
                instantOrNull(at));
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
     * @return the JSON to hold at the key
     */
    public String toJson() {
        final JsonValue value = JsonValue.newObject();
        final JsonObject object = value.asObject();
        object.putBoolean("enabled", enabled);
        if (reason != null) {
            object.putString("reason", reason);
        }
        if (at != null) {
            object.putString("at", at.toString());
        }
        final StringWriter json = new StringWriter();
        value.toJsonAndEoj(json);
        return json.toString();
    }
}
