package com.zipick.external.ai;

import java.util.List;

public record GeminiNormalizationResult(
        List<GeminiNormalizationCorrection> corrections,
        int plannedCalls,
        int executedCalls,
        int cacheHits,
        int estimatedInputTokens,
        List<String> deferredDocumentIds,
        List<String> warnings
) {
    public GeminiNormalizationResult {
        corrections = corrections == null ? List.of() : List.copyOf(corrections);
        deferredDocumentIds = deferredDocumentIds == null ? List.of() : List.copyOf(deferredDocumentIds);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
