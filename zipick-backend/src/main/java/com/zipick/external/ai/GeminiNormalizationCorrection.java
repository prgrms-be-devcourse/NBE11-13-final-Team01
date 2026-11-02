package com.zipick.external.ai;

/** 3.8 Flash는 전체 데이터를 재출력하지 않고 수정할 항목만 반환합니다. */
public record GeminiNormalizationCorrection(
        String documentId,
        String sourceHousingKey,
        String sourceSupplyKey,
        String target,
        String field,
        String stringValue,
        Long integerValue,
        String decimalValue,
        Boolean booleanValue,
        String attributeName,
        String attributeValue,
        String evidence,
        String reason
) {
}
