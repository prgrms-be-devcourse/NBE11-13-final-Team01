package com.zipick.external.ai;

import java.util.List;

public record GeminiPdfBatchExtractionResult(
        List<GeminiPdfExtractionResult> batchResults,
        int plannedBatchCount,
        int completedBatchCount,
        int apiCallCount,
        GeminiTokenUsage tokenUsage,
        List<String> deferredDocumentIds,
        boolean complete,
        List<String> warnings
) {
    public GeminiPdfBatchExtractionResult {
        batchResults = batchResults == null ? List.of() : List.copyOf(batchResults);
        tokenUsage = tokenUsage == null ? GeminiTokenUsage.empty() : tokenUsage;
        deferredDocumentIds = deferredDocumentIds == null ? List.of() : List.copyOf(deferredDocumentIds);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
