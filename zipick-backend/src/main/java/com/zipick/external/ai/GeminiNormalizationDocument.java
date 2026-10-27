package com.zipick.external.ai;

import java.util.Map;

/** Lite 원본과 서버의 DB 저장 후보를 함께 전달하는 검수 단위입니다. */
public record GeminiNormalizationDocument(
        String documentId,
        String agency,
        String noticeTitle,
        Map<String, Object> liteExtraction,
        Map<String, Object> databaseCandidate
) {
    public GeminiNormalizationDocument {
        liteExtraction = liteExtraction == null ? Map.of() : Map.copyOf(liteExtraction);
        databaseCandidate = databaseCandidate == null ? Map.of() : Map.copyOf(databaseCandidate);
    }
}
