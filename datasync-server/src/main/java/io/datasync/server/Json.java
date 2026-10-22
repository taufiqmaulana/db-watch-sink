package io.datasync.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.UncheckedIOException;
import java.util.Map;

/** JSON as stored in the state store's {@code *_json} columns. */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Map<String, Object> map(String json) {
        try {
            return json == null ? Map.of() : MAPPER.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
