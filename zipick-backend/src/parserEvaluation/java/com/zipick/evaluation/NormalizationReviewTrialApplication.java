package com.zipick.evaluation;

import com.zipick.external.ai.GeminiDbNormalizationReviewer;
import com.zipick.external.ai.GeminiNormalizationDocument;
import com.zipick.external.ai.GeminiNormalizationProperties;
import com.zipick.external.ai.GeminiNormalizationResult;
import com.zipick.external.ai.GeminiPdfParserProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 저장된 Lite 결과만 묶어 3.8 Flash 정규화 검수를 실제 호출로 확인합니다. */
public final class NormalizationReviewTrialApplication {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path RESULT_ROOT = Path.of(".parser-evaluation", "results");

    private NormalizationReviewTrialApplication() {
    }

    public static void main(String[] args) throws Exception {
        GeminiEvaluationSettings.Settings settings = GeminiEvaluationSettings.load();
        String reviewModel = System.getenv().getOrDefault("GEMINI_NORMALIZATION_MODEL", "gemini-3.8-flash");
        List<GeminiNormalizationDocument> documents = latestLiteDocuments();
        GeminiPdfParserProperties pdf = new GeminiPdfParserProperties(
                settings.key(), "https://generativelanguage.googleapis.com", settings.model(),
                true, 20 * 1024 * 1024L, 65_536,
                "medium", Duration.ofMinutes(5), 500, 20, 4,
                ".gemini-pdf-cache", 1, 120
        );
        GeminiNormalizationProperties normalization = new GeminiNormalizationProperties(
                true, reviewModel, "low", 16_384, 220_000, 235_000,
                20, 18, Duration.ofMinutes(5)
        );
        GeminiNormalizationResult result = new GeminiDbNormalizationReviewer(pdf, normalization).review(documents);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("model", reviewModel);
        report.put("documents", documents.stream().map(GeminiNormalizationDocument::documentId).toList());
        report.put("plannedCalls", result.plannedCalls());
        report.put("executedCalls", result.executedCalls());
        report.put("cacheHits", result.cacheHits());
        report.put("estimatedInputTokens", result.estimatedInputTokens());
        report.put("deferredDocumentIds", result.deferredDocumentIds());
        report.put("warnings", result.warnings());
        report.put("corrections", result.corrections());
        Path target = RESULT_ROOT.resolve("normalization-review-" + reviewModel + ".json");
        Files.writeString(target, JSON.writeValueAsString(report), StandardCharsets.UTF_8);
        System.out.println("Normalization review trial complete");
        System.out.println("documents=" + documents.size() + ", plannedCalls=" + result.plannedCalls()
                + ", executedCalls=" + result.executedCalls() + ", cacheHits=" + result.cacheHits());
        System.out.println("estimatedInputTokens=" + result.estimatedInputTokens()
                + ", corrections=" + result.corrections().size()
                + ", deferred=" + result.deferredDocumentIds().size());
        System.out.println("report=" + target.toAbsolutePath());
    }

    private static List<GeminiNormalizationDocument> latestLiteDocuments() throws Exception {
        Map<String, Path> latest = new LinkedHashMap<>();
        try (var files = Files.list(RESULT_ROOT)) {
            files.filter(path -> path.getFileName().toString()
                            .startsWith("hybrid-trial-gemini-3.5-flash-lite-medium-"))
                    .sorted(Comparator.comparingLong(NormalizationReviewTrialApplication::modified))
                    .forEach(path -> {
                        try {
                            JsonNode root = JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
                            latest.put(root.path("documentId").asText(), path);
                        } catch (Exception ignored) {
                            // 평가 결과가 아닌 파일은 제외합니다.
                        }
                    });
        }
        List<GeminiNormalizationDocument> result = new ArrayList<>();
        for (Path path : latest.values()) {
            JsonNode root = JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
            List<Object> liteRows = new ArrayList<>();
            root.path("units").forEach(unit -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sourceHousingKey", unit.path("address").asText());
                row.put("sourceSupplyKey", unit.path("sourceUnitKey").asText());
                String raw = unit.path("rawPayload").asText("");
                row.put("rawPayload", raw.isBlank() ? Map.of() : JSON.readValue(raw, Object.class));
                liteRows.add(row);
            });
            Map<String, Object> lite = new LinkedHashMap<>();
            lite.put("rows", liteRows);
            lite.put("documentAdditionalAttributes", root.path("additionalAttributes").isArray()
                    ? JSON.convertValue(root.path("additionalAttributes"), Object.class)
                    : List.of());
            result.add(new GeminiNormalizationDocument(
                    root.path("documentId").asText(), root.path("agency").asText("UNKNOWN"),
                    root.path("title").asText(root.path("documentId").asText()),
                    lite,
                    Map.of("rows", compactCandidates(root.path("units")))
            ));
        }
        if (result.isEmpty()) throw new IllegalStateException("저장된 Lite 평가 결과가 없습니다.");
        return result;
    }

    private static List<Map<String, Object>> compactCandidates(JsonNode units) {
        List<Map<String, Object>> result = new ArrayList<>();
        units.forEach(unit -> {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String field : List.of(
                    "sourceUnitKey", "complexName", "provinceName", "districtName", "address",
                    "lotAddress", "roadAddress", "buildingDong", "unitNumber", "floor", "roomCount",
                    "exclusiveArea", "housingType", "supplyType", "rentDeposit", "monthlyRent",
                    "supplyCount", "supplyStatus", "elevatorAvailable", "communityFacilityAvailable",
                    "totalParkingSpaces", "mechanicalParkingAvailable", "mechanicalParkingSpaces",
                    "rentalConditions"
            )) {
                JsonNode value = unit.path(field);
                boolean populated = value.isArray() || value.isObject()
                        ? !value.isEmpty() : !value.asText("").isBlank();
                if (!value.isMissingNode() && !value.isNull() && populated) {
                    row.put(field, JSON.convertValue(value, Object.class));
                }
            }
            result.add(row);
        });
        return result;
    }

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
            return 0;
        }
    }
}
