package com.zipick.external.ai;

import java.util.List;

public record GeminiPdfBatchPlan(
        List<List<GeminiPdfDocument>> batches,
        int documentCount,
        int estimatedInitialCalls
) {
    public GeminiPdfBatchPlan {
        batches = batches == null ? List.of() : batches.stream().map(List::copyOf).toList();
    }
}
