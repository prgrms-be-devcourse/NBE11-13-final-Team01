package com.zipick.external.ai;

import java.util.List;

public record GeminiNormalizationPlan(
        List<List<GeminiNormalizationDocument>> batches,
        int documentCount,
        int plannedCalls,
        int estimatedInputTokens,
        List<String> deferredDocumentIds
) {
    public GeminiNormalizationPlan {
        batches = batches == null ? List.of() : batches.stream().map(List::copyOf).toList();
        deferredDocumentIds = deferredDocumentIds == null ? List.of() : List.copyOf(deferredDocumentIds);
    }
}
