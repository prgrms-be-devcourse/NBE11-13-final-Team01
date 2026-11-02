package com.zipick.external.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiDbNormalizationReviewerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temporary;

    @Test
    void 원문에_있는_제목을_근거로_없는_주소를_만들면_제외한다() {
        GeminiApiTransport transport = request -> envelope(Map.of("valid", false, "corrections", List.of(Map.ofEntries(
                Map.entry("documentId", "doc-1"), Map.entry("sourceHousingKey", "housing-1"),
                Map.entry("sourceSupplyKey", ""), Map.entry("target", "HOUSING"), Map.entry("field", "provinceName"),
                Map.entry("stringValue", "광주광역시"), Map.entry("integerValue", 0), Map.entry("decimalValue", ""),
                Map.entry("booleanValue", false), Map.entry("attributeName", ""), Map.entry("attributeValue", ""),
                Map.entry("evidence", "광주두암2 영구임대주택"), Map.entry("reason", "주소 수정"))), "warnings", List.of()));
        Path cachePath=temporary.resolve("unsupported-address");
        var reviewer=new GeminiDbNormalizationReviewer(pdfProperties(), properties(true,100_000,200_000,16), transport,
                new GeminiPdfExtractionCache(cachePath),new GeminiDailyCallBudget(cachePath,20,4));
        var result=reviewer.review(List.of(document("doc-1", "광주두암2 영구임대주택")));
        assertThat(result.corrections()).isEmpty();
    }

    @Test
    void 일시_오류_후_재시도_성공은_최종_호출_실패로_기록하지_않는다() {
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> {
            if (calls.getAndIncrement() == 0) throw new IllegalStateException("status=503");
            return envelope(Map.of("valid", true, "corrections", List.of(), "warnings", List.of()));
        };
        Path cachePath = temporary.resolve("retry-success");
        var reviewer = new GeminiDbNormalizationReviewer(pdfProperties(), properties(true, 100_000, 200_000, 16),
                transport, new GeminiPdfExtractionCache(cachePath), new GeminiDailyCallBudget(cachePath, 20, 4));
        Logger logger = (Logger) LoggerFactory.getLogger(GeminiFinalFailureLogger.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(reviewer.review(List.of(document("doc-1", "남여공용"))).deferredDocumentIds()).isEmpty();
            assertThat(calls).hasValue(2);
            assertThat(appender.list).isEmpty();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void 재시도까지_실패하면_최종_호출_실패를_기록한다() {
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> {
            calls.incrementAndGet();
            throw new IllegalStateException("status=503");
        };
        Path cachePath = temporary.resolve("retry-failure");
        var reviewer = new GeminiDbNormalizationReviewer(pdfProperties(), properties(true, 100_000, 200_000, 16),
                transport, new GeminiPdfExtractionCache(cachePath), new GeminiDailyCallBudget(cachePath, 20, 4));
        Logger logger = (Logger) LoggerFactory.getLogger(GeminiFinalFailureLogger.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(reviewer.review(List.of(document("doc-1", "남여공용"))).deferredDocumentIds())
                    .containsExactly("doc-1");
            assertThat(calls).hasValue(2);
            assertThat(appender.list).singleElement().satisfies(event ->
                    assertThat(event.getFormattedMessage()).contains("stage=normalization", "status=503"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void 여러문서를_토큰크기로_묶고_16회가_넘는_문서는_연기한다() {
        GeminiNormalizationProperties properties = properties(true, 4_000, 8_000, 2);
        GeminiNormalizationBatchPlanner planner = new GeminiNormalizationBatchPlanner(properties);
        List<GeminiNormalizationDocument> documents = java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(index -> document("doc-" + index, "근거".repeat(1_500)))
                .toList();

        GeminiNormalizationPlan plan = planner.plan(documents);

        assertThat(plan.plannedCalls()).isLessThanOrEqualTo(2);
        assertThat(plan.documentCount()).isEqualTo(8);
        assertThat(plan.deferredDocumentIds()).isNotEmpty();
    }

    @Test
    void 칠십개_문서를_220K_기준으로_20회_안에_계획한다() {
        GeminiNormalizationProperties properties = properties(true, 220_000, 235_000, 18);
        GeminiNormalizationBatchPlanner planner = new GeminiNormalizationBatchPlanner(properties);
        List<GeminiNormalizationDocument> documents = java.util.stream.IntStream.rangeClosed(1, 70)
                .mapToObj(index -> document("doc-" + index, "주택근거".repeat(3_000)))
                .toList();

        GeminiNormalizationPlan plan = planner.plan(documents);

        assertThat(plan.documentCount()).isEqualTo(70);
        assertThat(plan.plannedCalls()).isLessThanOrEqualTo(6);
        assertThat(plan.deferredDocumentIds()).isEmpty();
        assertThat(plan.batches()).allSatisfy(batch -> {
            int tokens = batch.stream().mapToInt(planner::estimateTokens).sum() + 2_500;
            assertThat(tokens).isLessThanOrEqualTo(235_000);
        });
    }

    @Test
    void 수정사항만_받고_같은입력은_캐시해_재호출하지_않는다() {
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> {
            calls.incrementAndGet();
            assertThat(request).contains("수정이 필요한 항목만", "databaseCandidate", "liteExtraction");
            return envelope(Map.of(
                    "valid", false,
                    "corrections", List.of(Map.ofEntries(
                            Map.entry("documentId", "doc-1"),
                            Map.entry("sourceHousingKey", "housing-1"),
                            Map.entry("sourceSupplyKey", "supply-1"),
                            Map.entry("target", "ATTRIBUTE"),
                            Map.entry("field", "additionalAttributes"),
                            Map.entry("stringValue", "남여공용"),
                            Map.entry("integerValue", 0),
                            Map.entry("decimalValue", ""),
                            Map.entry("booleanValue", false),
                            Map.entry("attributeName", "성별용도"),
                            Map.entry("attributeValue", "남여공용"),
                            Map.entry("evidence", "남여공용"),
                            Map.entry("reason", "원본에는 있으나 DB 후보에서 누락")
                    )),
                    "warnings", List.of()
            ));
        };
        GeminiPdfParserProperties pdf = pdfProperties();
        GeminiNormalizationProperties normalization = properties(true, 100_000, 200_000, 16);
        Path cachePath = temporary.resolve("normalization");
        GeminiDbNormalizationReviewer reviewer = new GeminiDbNormalizationReviewer(
                pdf, normalization, transport,
                new GeminiPdfExtractionCache(cachePath),
                new GeminiDailyCallBudget(cachePath, 20, 4)
        );
        GeminiNormalizationDocument document = document("doc-1", "남여공용");

        GeminiNormalizationResult first = reviewer.review(List.of(document));
        GeminiNormalizationResult second = reviewer.review(List.of(document));

        assertThat(first.corrections()).hasSize(1);
        assertThat(first.executedCalls()).isEqualTo(1);
        assertThat(second.executedCalls()).isZero();
        assertThat(second.cacheHits()).isEqualTo(1);
        assertThat(calls).hasValue(1);
    }

    @Test
    void 입력에_없는_근거를_제시한_수정은_버린다() {
        GeminiApiTransport transport = request -> envelope(Map.of(
                "valid", false,
                "corrections", List.of(Map.ofEntries(
                        Map.entry("documentId", "doc-1"),
                        Map.entry("sourceHousingKey", "housing-1"),
                        Map.entry("sourceSupplyKey", "supply-1"),
                        Map.entry("target", "ATTRIBUTE"), Map.entry("field", ""),
                        Map.entry("stringValue", ""), Map.entry("integerValue", 0),
                        Map.entry("decimalValue", ""), Map.entry("booleanValue", false),
                        Map.entry("attributeName", "옥상출입"), Map.entry("attributeValue", "가능"),
                        Map.entry("evidence", "입력에 없는 문장"), Map.entry("reason", "추정")
                )), "warnings", List.of()
        ));
        Path cachePath = temporary.resolve("invalid-evidence");
        GeminiDbNormalizationReviewer reviewer = new GeminiDbNormalizationReviewer(
                pdfProperties(), properties(true, 100_000, 200_000, 16), transport,
                new GeminiPdfExtractionCache(cachePath), new GeminiDailyCallBudget(cachePath, 20, 4));

        GeminiNormalizationResult result = reviewer.review(List.of(document("doc-1", "남여공용")));

        assertThat(result.corrections()).isEmpty();
        assertThat(result.warnings()).anyMatch(value -> value.contains("근거"));
    }

    @Test
    void 상위모델이_장애여도_전체수집을_실패시키지_않고_배치를_연기한다() {
        GeminiApiTransport transport = request -> {
            throw new IllegalStateException("503 high demand");
        };
        Path cachePath = temporary.resolve("unavailable");
        GeminiDbNormalizationReviewer reviewer = new GeminiDbNormalizationReviewer(
                pdfProperties(), properties(true, 100_000, 200_000, 16), transport,
                new GeminiPdfExtractionCache(cachePath), new GeminiDailyCallBudget(cachePath, 20, 4));

        GeminiNormalizationResult result = reviewer.review(List.of(document("doc-1", "남여공용")));

        assertThat(result.corrections()).isEmpty();
        assertThat(result.deferredDocumentIds()).containsExactly("doc-1");
        assertThat(result.warnings()).anyMatch(value -> value.contains("503 high demand"));
    }

    @Test
    void 해석할수없는_응답은_캐시하지않아_다음실행에서_다시호출한다() {
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> {
            calls.incrementAndGet();
            return "{\"candidates\":[]}";
        };
        Path cachePath = temporary.resolve("malformed");
        GeminiDbNormalizationReviewer reviewer = new GeminiDbNormalizationReviewer(
                pdfProperties(), properties(true, 100_000, 200_000, 16), transport,
                new GeminiPdfExtractionCache(cachePath), new GeminiDailyCallBudget(cachePath, 20, 4));
        GeminiNormalizationDocument document = document("doc-1", "남여공용");

        GeminiNormalizationResult first = reviewer.review(List.of(document));
        GeminiNormalizationResult second = reviewer.review(List.of(document));

        assertThat(first.deferredDocumentIds()).containsExactly("doc-1");
        assertThat(second.cacheHits()).isZero();
        assertThat(calls).hasValue(2);
    }

    private GeminiNormalizationDocument document(String id, String evidence) {
        return new GeminiNormalizationDocument(id, "LH", "2026년 공고",
                Map.of("rows", List.of(Map.of("sourceSupplyKey", "supply-1", "evidence", evidence))),
                Map.of("rows", List.of(Map.of("sourceHousingKey", "housing-1",
                        "sourceSupplyKey", "supply-1"))));
    }

    private GeminiNormalizationProperties properties(
            boolean enabled, int target, int max, int normalLimit
    ) {
        return new GeminiNormalizationProperties(enabled, "gemini-3.8-flash", "low",
                16_384, target, max, 20, normalLimit, Duration.ofSeconds(10));
    }

    private GeminiPdfParserProperties pdfProperties() {
        return new GeminiPdfParserProperties("key", "https://generativelanguage.googleapis.com",
                "gemini-3.5-flash-lite", true,
                20 * 1024 * 1024L, 65_536, "medium", Duration.ofSeconds(10),
                500, 20, 4, 50, temporary.resolve("pdf").toString(), 1, 120);
    }

    private String envelope(Map<String, Object> payload) {
        return JSON.writeValueAsString(Map.of("candidates", List.of(Map.of(
                "content", Map.of("parts", List.of(Map.of("text", JSON.writeValueAsString(payload))))
        ))));
    }
}
