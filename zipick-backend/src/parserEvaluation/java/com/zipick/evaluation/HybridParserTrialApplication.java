package com.zipick.evaluation;

import com.zipick.external.ai.GeminiPdfParserProperties;
import com.zipick.external.ai.GeminiPdfHousingExtractor;
import com.zipick.external.ai.HybridPdfHousingParser;
import com.zipick.external.ai.GeminiPdfExtractionResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Gemini가 PDF 전체에서 상세주택을 직접 추출하는 수동 평가 도구다. */
public final class HybridParserTrialApplication {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HybridParserTrialApplication() {
    }

    public static void main(String[] args) throws Exception {
        String documentId = System.getenv().getOrDefault(
                "PARSER_EVALUATION_DOCUMENT_ID", "gh-795-purchase-rental-housing-list"
        );
        Path resourceRoot = Path.of(
                "zipick-backend", "src", "parserEvaluation", "resources", "parser-evaluation"
        );
        JsonNode manifest = JSON.readTree(Files.readString(
                resourceRoot.resolve("manifest.json"), StandardCharsets.UTF_8
        ));
        JsonNode selected = null;
        for (JsonNode document : manifest.path("documents")) {
            if (documentId.equals(document.path("id").asText())) {
                selected = document;
                break;
            }
        }
        if (selected == null) throw new IllegalStateException("평가 문서를 찾지 못했습니다: " + documentId);

        Path pdf = Path.of(".parser-evaluation", "documents", selected.path("localFile").asText());
        byte[] bytes = Files.readAllBytes(pdf);
        GeminiEvaluationSettings.Settings settings = GeminiEvaluationSettings.load();
        String thinkingLevel = System.getenv().getOrDefault("GEMINI_THINKING_LEVEL", "medium");
        GeminiPdfParserProperties properties = new GeminiPdfParserProperties(
                settings.key(), "https://generativelanguage.googleapis.com", settings.model(),
                true, 20 * 1024 * 1024L,
                65_536, thinkingLevel, Duration.ofMinutes(5), 500, 20,
                Integer.parseInt(System.getenv().getOrDefault("GEMINI_MAX_CONTINUATIONS", "4")),
                ".gemini-pdf-cache", 1, 120
        );
        long started = System.nanoTime();
        HybridPdfHousingParser parser = new HybridPdfHousingParser(new GeminiPdfHousingExtractor(properties));
        GeminiPdfExtractionResult result = parser.parse(
                bytes, documentId, selected.path("agency").asText(), selected.path("title").asText(),
                selected.path("expectedUnitCount").asInt()
        );
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("documentId", documentId);
        report.put("model", settings.model());
        report.put("thinkingLevel", thinkingLevel);
        report.put("geminiCalls", result.apiCallCount());
        report.put("elapsedMillis", Duration.ofNanos(System.nanoTime() - started).toMillis());
        report.put("expectedRowCount", result.expectedUnitCount());
        report.put("parsedUnitCount", result.units().size());
        report.put("complete", result.complete());
        report.put("fromCache", result.fromCache());
        report.put("tokenUsage", result.tokenUsage());
        report.put("relevantPages", result.relevantPages());
        report.put("additionalAttributes", result.additionalAttributes());
        report.put("warnings", result.warnings());
        report.put("units", result.units());

        String resultKey = (settings.model() + "-" + thinkingLevel)
                .replaceAll("[^a-zA-Z0-9._-]", "-");
        Path target = Path.of(".parser-evaluation", "results",
                "hybrid-trial-" + resultKey + "-" + documentId + ".json");
        Files.writeString(target, JSON.writeValueAsString(report), StandardCharsets.UTF_8);
        System.out.println("Hybrid PDF parser trial complete");
        System.out.println("geminiCalls=" + result.apiCallCount()
                + ", units=" + result.units().size() + "/" + result.expectedUnitCount());
        System.out.println("complete=" + result.complete() + ", cache=" + result.fromCache());
        System.out.println("warnings=" + result.warnings());
        System.out.println("report=" + target.toAbsolutePath());
    }
}
