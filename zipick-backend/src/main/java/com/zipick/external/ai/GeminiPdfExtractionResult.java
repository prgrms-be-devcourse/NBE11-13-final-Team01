package com.zipick.external.ai;

import com.zipick.external.housingdetail.DetailedHousingUnit;

import java.util.List;
import java.util.Map;

public record GeminiPdfExtractionResult(
        List<DetailedHousingUnit> units,
        boolean complete,
        String nextCursor,
        int expectedUnitCount,
        int apiCallCount,
        boolean fromCache,
        GeminiTokenUsage tokenUsage,
        List<String> relevantPages,
        List<Map<String, Object>> additionalAttributes,
        List<String> warnings
) {
    public GeminiPdfExtractionResult {
        units = units == null ? List.of() : List.copyOf(units);
        relevantPages = relevantPages == null ? List.of() : List.copyOf(relevantPages);
        additionalAttributes = additionalAttributes == null ? List.of() : List.copyOf(additionalAttributes);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        tokenUsage = tokenUsage == null ? GeminiTokenUsage.empty() : tokenUsage;
    }
}
