package com.battleship.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Shared JSON entry point.
 *
 * The Lab 5 version built protocol strings with concatenation, which quietly
 * broke on any chat message containing a quote, backslash or newline. A real
 * serializer removes that whole class of bug — and the injection risk that
 * comes with it.
 *
 * {@link ObjectMapper} is thread-safe once configured, so one instance is
 * shared by every connection thread.
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private Json() {}

    public static ObjectNode object() { return MAPPER.createObjectNode(); }

    public static ArrayNode array() { return MAPPER.createArrayNode(); }

    public static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize JSON node", e);
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize " + value, e);
        }
    }

    /** Parses a client payload. Returns null if it is not a JSON object. */
    public static ObjectNode parseObject(String raw) {
        try {
            JsonNode node = MAPPER.readTree(raw);
            return node instanceof ObjectNode obj ? obj : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** 2D int grid → JSON array of arrays. */
    public static ArrayNode grid(int[][] cells) {
        ArrayNode rows = array();
        for (int[] row : cells) {
            ArrayNode cols = array();
            for (int cell : row) cols.add(cell);
            rows.add(cols);
        }
        return rows;
    }
}
