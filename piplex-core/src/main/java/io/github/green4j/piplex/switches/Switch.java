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

/**
 * Whether work is switched on, and why it was switched off.
 *
 * <p>This replaces the commonest piece of orchestration folklore there is: commenting a schedule out of
 * one deployment's configuration and redeploying it by hand. An operator flips it, every controller sees
 * it, and a run already in flight is stopped by the same machinery that stops one whose owner changed.
 *
 * <p>This is configuration, and pretending otherwise would only make the boundary harder to explain. The
 * boundary is not between configuration and something else; it is between two things a value can need.
 * A switch needs to change without a deploy and to be agreed on everywhere at once, and it is worth
 * giving up review and history to get that.
 *
 * <p>Bucket names, regions, image tags and job timeouts need the opposite -- review before the change,
 * history after it, rollback, and the ability to change two of them as one -- and none of that is on
 * offer here. They stay in version control. When in doubt the question is not what the value is called
 * but how fast it has to move, and what is being traded for the speed.
 *
 * @param enabled whether the work may run
 * @param reason  why it was switched off, may be {@code null}
 */
public record Switch(boolean enabled, String reason) {

    /** What an absent key means: on. Switching off is the deliberate act, never the default. */
    public static final Switch ENABLED = new Switch(true, null);

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
            throw new IllegalArgumentException("not a JSON object: " + text, notAnObject);
        }
        return new Switch(object.getBooleanRequired("enabled"), object.getString("reason"));
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
        final StringWriter json = new StringWriter();
        value.toJsonAndEoj(json);
        return json.toString();
    }
}
