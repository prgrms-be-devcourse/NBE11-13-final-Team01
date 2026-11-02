package com.zipick.external.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lite 추출값과 DB 후보를 3.8 Flash가 비교하고 수정사항만 반환하도록 합니다. */
@Component
public class GeminiDbNormalizationReviewer {

    static final String PROMPT_VERSION = "DB_NORMALIZATION_PATCH_V2_FIELD_WHITELIST";
    private static final Logger log = LoggerFactory.getLogger(GeminiDbNormalizationReviewer.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final GeminiPdfParserProperties pdfProperties;
    private final GeminiNormalizationProperties properties;
    private final GeminiApiTransport transport;
    private final GeminiPdfExtractionCache cache;
    private final GeminiDailyCallBudget budget;

    @Autowired
    public GeminiDbNormalizationReviewer(
            GeminiPdfParserProperties pdfProperties,
            GeminiNormalizationProperties properties
    ) {
        this(pdfProperties, properties,
                new GeminiHttpTransport(pdfProperties.baseUrl(), pdfProperties.key(),
                        properties.model(), properties.timeout()),
                new GeminiPdfExtractionCache(pdfProperties.cachePath().resolve("normalization")),
                new GeminiDailyCallBudget(pdfProperties.cachePath().resolve("normalization"),
                        properties.dailyCallLimit(), properties.dailyCallLimit() - properties.normalCallLimit()));
    }

    GeminiDbNormalizationReviewer(
            GeminiPdfParserProperties pdfProperties,
            GeminiNormalizationProperties properties,
            GeminiApiTransport transport,
            GeminiPdfExtractionCache cache,
            GeminiDailyCallBudget budget
    ) {
        this.pdfProperties = pdfProperties;
        this.properties = properties;
        this.transport = transport;
        this.cache = cache;
        this.budget = budget;
    }

    public boolean enabled() {
        return properties.enabled() && pdfProperties.isConfigured();
    }

    public GeminiNormalizationResult review(List<GeminiNormalizationDocument> documents) {
        if (!enabled() || documents == null || documents.isEmpty()) {
            return new GeminiNormalizationResult(List.of(), 0, 0, 0, 0, List.of(), List.of());
        }
        GeminiNormalizationPlan plan = new GeminiNormalizationBatchPlanner(properties).plan(documents);
        List<GeminiNormalizationCorrection> corrections = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> deferred = new ArrayList<>(plan.deferredDocumentIds());
        int calls = 0;
        int cacheHits = 0;
        for (List<GeminiNormalizationDocument> batch : plan.batches()) {
            String input = JSON.writeValueAsString(batch);
            String key = cacheKey(input);
            String payload;
            boolean generated = false;
            var cached = cache.read(key);
            if (cached.isPresent()) {
                payload = cached.get();
                cacheHits++;
            } else {
                try {
                    CallResult call = callWithOneRetry(requestBody(input));
                    payload = call.payload();
                    calls += call.calls();
                    generated = true;
                } catch (RuntimeException exception) {
                    calls += 2;
                    batch.forEach(document -> deferred.add(document.documentId()));
                    warnings.add("3.8 Flash 장애로 배치를 다음 실행으로 연기했습니다: "
                            + batch.stream().map(GeminiNormalizationDocument::documentId).toList()
                            + ", reason=" + exception.getMessage());
                    continue;
                }
            }
            try {
                corrections.addAll(validCorrections(parseCorrections(payload), batch, warnings));
                // 구조 검증을 통과한 응답만 저장해 잘못된 캐시가 재시도를 영구 차단하지 않게 한다.
                if (generated) cache.write(key, payload);
            } catch (RuntimeException exception) {
                batch.forEach(document -> deferred.add(document.documentId()));
                warnings.add("3.8 Flash 응답을 해석하지 못해 다음 실행으로 연기했습니다: "
                        + batch.stream().map(GeminiNormalizationDocument::documentId).toList()
                        + ", reason=" + exception.getMessage());
            }
        }
        if (!deferred.isEmpty()) {
            warnings.add("일일 정상 호출 한도 또는 입력 한도를 초과해 다음 실행으로 연기했습니다: "
                    + deferred);
        }
        log.info("Gemini DB 정규화 검수 완료: documents={}, plannedCalls={}, apiCalls={}, cacheHits={}, "
                        + "corrections={}, deferred={}",
                plan.documentCount(), plan.plannedCalls(), calls, cacheHits,
                corrections.size(), deferred.size());
        return new GeminiNormalizationResult(corrections, plan.plannedCalls(), calls, cacheHits,
                plan.estimatedInputTokens(), deferred.stream().distinct().toList(), warnings);
    }

    private CallResult callWithOneRetry(String requestBody) {
        budget.acquire(false);
        try {
            return new CallResult(transport.generate(requestBody), 1);
        } catch (RuntimeException first) {
            log.warn("Gemini DB 정규화 첫 호출 실패, 예약 호출로 한 번 재시도합니다: {}", first.getMessage());
            try {
                budget.acquire(true);
            } catch (RuntimeException budgetFailure) {
                GeminiFinalFailureLogger.logFailure("normalization", first);
                throw budgetFailure;
            }
            try {
                return new CallResult(transport.generate(requestBody), 2);
            } catch (RuntimeException last) {
                GeminiFinalFailureLogger.logFailure("normalization", last);
                throw last;
            }
        }
    }

    private String requestBody(String input) {
        Map<String, Object> generation = new LinkedHashMap<>();
        generation.put("temperature", 0.0);
        generation.put("maxOutputTokens", properties.maxOutputTokens());
        generation.put("thinkingConfig", Map.of("thinkingLevel", properties.thinkingLevel()));
        generation.put("responseMimeType", "application/json");
        generation.put("responseJsonSchema", responseSchema());
        return JSON.writeValueAsString(Map.of(
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of(
                        "text", prompt() + "\n\n[검수 대상 JSON]\n" + input
                )))),
                "generationConfig", generation
        ));
    }

    private String prompt() {
        return """
                당신은 한국 공공임대 데이터의 최종 DB 정규화 검수기입니다.
                각 문서에는 3.5 Flash Lite의 원본 추출값(liteExtraction)과 서버가 만든 DB 저장 후보(databaseCandidate)가 있습니다.
                PDF를 다시 파싱하지 말고 두 JSON을 비교하세요. 올바른 기존 값은 절대 다시 출력하지 말고 수정이 필요한 항목만 corrections에 반환하세요.

                - Lite 원본이나 evidence에 없는 사실은 만들지 마세요.
                - 복합 정보는 원자 단위로 분리하세요. 예: 임대기간 2년/재계약 3회/최장 8년, 신청 시작/마감.
                - DB 고정 필드에 해당하면 HOUSING 또는 SUPPLY 수정으로 반환하세요.
                - 고정 필드가 없는 성별용도, 열람일정, 베란다, 옥상출입, 옵션 등은 ATTRIBUTE로 반환하세요.
                - DOCUMENT field는 applicationStartDate, applicationEndDate, winnerAnnouncementDate만 허용됩니다.
                - HOUSING field는 complexName, provinceName, districtName, fullAddress, lotAddress, roadAddress만 허용됩니다.
                - SUPPLY field는 houseTypeName, supplyTypeName, buildingDong, unitNumber, floor, roomCount,
                  exclusiveArea, rentDeposit, monthlyRent, supplyCount, supplyStatus, elevatorAvailable,
                  communityFacilityAvailable, totalParkingSpaces, mechanicalParkingAvailable,
                  mechanicalParkingSpaces만 허용됩니다.
                - 금액은 원 단위 정수, 면적은 제곱미터 소수, 날짜시간은 yyyy-MM-dd HH:mm으로 정규화하세요.
                - 같은 공급표에서 일부 행에만 금액·면적이 NULL이면 각 행의 rawPayload evidence를 다시 확인해
                  누락된 DB 후보를 보완하세요. 다만 원문이 '공고문 확인', '-', 별도 문의인 값은 추측하지 마세요.
                - sourceHousingKey/sourceSupplyKey는 입력값을 그대로 사용하세요.
                - ATTRIBUTE는 attributeName과 attributeValue를 반드시 채우세요.
                - 모든 수정에는 입력 JSON에 실제 포함된 짧은 evidence와 수정 이유를 적으세요.
                - corrections가 없으면 빈 배열과 valid=true를 반환하세요.
                """;
    }

    private Map<String, Object> responseSchema() {
        Map<String, Object> nullableString = Map.of("type", "string", "nullable", true);
        Map<String, Object> correction = object(Map.ofEntries(
                Map.entry("documentId", Map.of("type", "string")),
                Map.entry("sourceHousingKey", nullableString),
                Map.entry("sourceSupplyKey", nullableString),
                Map.entry("target", enumString("DOCUMENT", "HOUSING", "SUPPLY", "ATTRIBUTE")),
                Map.entry("field", nullableString),
                Map.entry("stringValue", nullableString),
                Map.entry("integerValue", Map.of("type", "integer", "nullable", true)),
                Map.entry("decimalValue", nullableString),
                Map.entry("booleanValue", Map.of("type", "boolean", "nullable", true)),
                Map.entry("attributeName", nullableString),
                Map.entry("attributeValue", nullableString),
                Map.entry("evidence", Map.of("type", "string")),
                Map.entry("reason", Map.of("type", "string"))
        ), List.of("documentId", "sourceHousingKey", "sourceSupplyKey", "target", "field",
                "stringValue", "integerValue", "decimalValue", "booleanValue",
                "attributeName", "attributeValue", "evidence", "reason"));
        return object(Map.of(
                "valid", Map.of("type", "boolean"),
                "corrections", Map.of("type", "array", "items", correction),
                "warnings", Map.of("type", "array", "items", Map.of("type", "string"))
        ), List.of("valid", "corrections", "warnings"));
    }

    private Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties,
                "required", required, "additionalProperties", false);
    }

    private Map<String, Object> enumString(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }

    private List<GeminiNormalizationCorrection> parseCorrections(String response) {
        JsonNode envelope = JSON.readTree(response);
        StringBuilder text = new StringBuilder();
        envelope.path("candidates").path(0).path("content").path("parts")
                .forEach(part -> text.append(part.path("text").asText()));
        if (text.isEmpty()) throw new IllegalStateException("Gemini DB 정규화 응답이 비어 있습니다.");
        JsonNode root = JSON.readTree(text.toString());
        List<GeminiNormalizationCorrection> result = new ArrayList<>();
        root.path("corrections").forEach(node -> result.add(JSON.convertValue(
                node, GeminiNormalizationCorrection.class)));
        return result;
    }

    private List<GeminiNormalizationCorrection> validCorrections(
            List<GeminiNormalizationCorrection> source,
            List<GeminiNormalizationDocument> batch,
            List<String> warnings
    ) {
        Map<String, String> rawByDocument = new LinkedHashMap<>();
        batch.forEach(document -> rawByDocument.put(document.documentId(), JSON.writeValueAsString(document)));
        List<GeminiNormalizationCorrection> result = new ArrayList<>();
        for (GeminiNormalizationCorrection correction : source) {
            String raw = rawByDocument.get(correction.documentId());
            if (raw == null) {
                warnings.add("알 수 없는 문서 수정은 제외했습니다: " + correction.documentId());
                continue;
            }
            if (correction.evidence() == null || correction.evidence().isBlank()
                    || !compact(raw).contains(compact(correction.evidence()))) {
                warnings.add("원문 근거를 확인할 수 없는 수정은 제외했습니다: "
                        + correction.documentId() + "/" + correction.field());
                continue;
            }
            if ("HOUSING".equals(correction.target())
                    && correction.field() != null
                    && java.util.Set.of("provinceName", "districtName", "fullAddress", "roadAddress", "lotAddress").contains(correction.field())
                    && (correction.stringValue() == null || correction.stringValue().isBlank()
                    || !compact(correction.evidence()).contains(compact(correction.stringValue())))) {
                warnings.add("주소 수정값이 제시된 원문 근거에 없어 제외했습니다: " + correction.documentId() + "/" + correction.field());
                continue;
            }
            if (("SUPPLY".equals(correction.target()) || "ATTRIBUTE".equals(correction.target()))
                    && (correction.sourceSupplyKey() == null || correction.sourceSupplyKey().isBlank()
                    || !raw.contains(correction.sourceSupplyKey()))) {
                warnings.add("대상 공급 호실을 확인할 수 없는 수정은 제외했습니다: "
                        + correction.documentId() + "/" + correction.sourceSupplyKey());
                continue;
            }
            if ("HOUSING".equals(correction.target())
                    && (correction.sourceHousingKey() == null || correction.sourceHousingKey().isBlank()
                    || !raw.contains(correction.sourceHousingKey()))) {
                warnings.add("대상 주택을 확인할 수 없는 수정은 제외했습니다: "
                        + correction.documentId() + "/" + correction.sourceHousingKey());
                continue;
            }
            if ("ATTRIBUTE".equals(correction.target())
                    && (correction.attributeName() == null || correction.attributeName().isBlank()
                    || correction.attributeValue() == null || correction.attributeValue().isBlank())) {
                warnings.add("이름이나 값이 없는 추가 속성 수정은 제외했습니다: " + correction.documentId());
                continue;
            }
            result.add(correction);
        }
        return result;
    }

    private String compact(String value) {
        return value == null ? "" : value.replaceAll("[^0-9A-Za-z가-힣]", "").toLowerCase();
    }

    private String cacheKey(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    (PROMPT_VERSION + "|" + properties.model() + "|" + input)
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("정규화 캐시 키를 만들지 못했습니다.", exception);
        }
    }

    private record CallResult(String payload, int calls) {
    }
}
