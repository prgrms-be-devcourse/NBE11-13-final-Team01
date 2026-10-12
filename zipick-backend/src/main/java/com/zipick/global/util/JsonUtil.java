package com.zipick.global.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.Map;

/** 엔티티의 JSON 컬럼 직렬화/역직렬화 헬퍼. */
public final class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonUtil() {}

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 직렬화 실패", e);
        }
    }

    public static <T> T read(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) return fallback;
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            return fallback;
        }
    }

    public static Map<String, Integer> readIntMap(String json) {
        return read(json, new TypeReference<Map<String, Integer>>() {}, Collections.emptyMap());
    }
}
