/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.milestone;

import io.github.green4j.jelly.simple.JsonObject;
import io.github.green4j.jelly.simple.JsonValue;
import io.github.green4j.jelly.simple.JsonValueParser;
import io.github.green4j.piplex.Generation;

import java.io.StringWriter;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * What a producer got as far as: the record held at a milestone key.
 *
 * <p>Only {@code generation} carries meaning to piplex. The rest is there so that somebody reading the
 * key can see who put it there and when without opening another system.
 *
 * @param generation how far the producer got
 * @param by         the owner which published it, may be {@code null}
 * @param runId      the run which published it, may be {@code null}
 * @param at         when it was published, may be {@code null}
 */
public record Milestone(Generation generation, String by, String runId, Instant at) {

    /**
     * @param generation how far the producer got
     * @param by         the owner which published it
     * @param runId      the run which published it
     * @param at         when
     */
    public Milestone {
        Objects.requireNonNull(generation, "generation");
    }

    /**
     * Reads a record.
     *
     * @param text the JSON held at the key
     * @return the record
     */
    public static Milestone parse(final String text) {
        final JsonObject object;
        try {
            object = new JsonValueParser().parseAndEoj(text).asObjectRequired();
        } catch (final RuntimeException notAnObject) {
            throw new IllegalArgumentException("Not a JSON object: " + text, notAnObject);
        }
        final String at = object.getString("at");
        return new Milestone(
                Generation.of(object.getStringRequired("generation")),
                object.getString("by"),
                object.getString("runId"),
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
        object.putString("generation", generation.value());
        if (by != null) {
            object.putString("by", by);
        }
        if (runId != null) {
            object.putString("runId", runId);
        }
        if (at != null) {
            object.putString("at", at.toString());
        }
        final StringWriter json = new StringWriter();
        value.toJsonAndEoj(json);
        return json.toString();
    }
}
