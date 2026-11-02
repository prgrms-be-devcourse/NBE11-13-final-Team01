package com.zipick.external.ai;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** JSON 크기를 보수적으로 토큰으로 환산해 RPD 20 안에서 검수 배치를 구성합니다. */
public final class GeminiNormalizationBatchPlanner {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int PROMPT_OVERHEAD_TOKENS = 2_500;

    private final GeminiNormalizationProperties properties;

    public GeminiNormalizationBatchPlanner(GeminiNormalizationProperties properties) {
        this.properties = properties;
    }

    public GeminiNormalizationPlan plan(List<GeminiNormalizationDocument> documents) {
        List<SizedDocument> sized = documents.stream()
                .map(document -> new SizedDocument(document, estimateTokens(document)))
                .sorted(Comparator.comparingInt(SizedDocument::tokens).reversed())
                .toList();
        List<MutableBatch> batches = new ArrayList<>();
        List<String> deferred = new ArrayList<>();
        int contentTokens = 0;
        for (SizedDocument item : sized) {
            contentTokens += item.tokens();
            if (item.tokens() + PROMPT_OVERHEAD_TOKENS > properties.maxInputTokens()) {
                deferred.add(item.document().documentId());
                continue;
            }
            MutableBatch selected = batches.stream()
                    .filter(batch -> batch.tokens + item.tokens() <= properties.targetInputTokens())
                    .min(Comparator.comparingInt(batch -> properties.targetInputTokens() - batch.tokens - item.tokens()))
                    .orElse(null);
            if (selected == null) {
                if (batches.size() >= properties.normalCallLimit()) {
                    deferred.add(item.document().documentId());
                    continue;
                }
                selected = new MutableBatch();
                batches.add(selected);
            }
            selected.add(item);
        }
        return new GeminiNormalizationPlan(
                batches.stream().map(batch -> batch.documents.stream()
                        .map(SizedDocument::document).toList()).toList(),
                documents.size(), batches.size(),
                contentTokens + batches.size() * PROMPT_OVERHEAD_TOKENS, deferred
        );
    }

    public int estimateTokens(GeminiNormalizationDocument document) {
        try {
            int bytes = JSON.writeValueAsString(document).getBytes(StandardCharsets.UTF_8).length;
            return (int) Math.ceil(bytes / 3.0);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("정규화 후보 JSON 크기를 계산하지 못했습니다: "
                    + document.documentId(), exception);
        }
    }

    private record SizedDocument(GeminiNormalizationDocument document, int tokens) {
    }

    private static final class MutableBatch {
        private final List<SizedDocument> documents = new ArrayList<>();
        private int tokens = PROMPT_OVERHEAD_TOKENS;

        void add(SizedDocument document) {
            documents.add(document);
            tokens += document.tokens();
        }
    }
}
