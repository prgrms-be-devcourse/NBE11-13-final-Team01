package com.zipick.external.ai;

import com.zipick.external.housingdetail.DetailedHousingUnit;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiPdfHousingExtractorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temporary;

    @Test
    void 텍스트에서_주택형_숫자를_면적으로_추정하지_않는다() {
        var area = new java.math.BigDecimal("16.00");
        assertThat(GeminiPdfHousingExtractor.explicitAreaInEvidence("16A 382세대", area)).isFalse();
        assertThat(GeminiPdfHousingExtractor.explicitAreaInEvidence("전용면적 16.00㎡", area)).isTrue();
    }

    @Test
    void 같은_주택형이라도_기본금액이_다르면_전환금액을_혼합하지_않는다() {
        String page = "임대보증금(천원) 최대전환시 월임대료\n"
                + "(+) 28,000 86,480 93,920\n대학생 58,480 2,924 55,556 233,920\n"
                + "(-) 49,000 9,480 376,830\n(+) 29,000 90,920 102,680\n"
                + "16A 청년 61,920 3,096 58,824 247,680\n(-) 52,000 9,920 399,340";
        var pages = Map.of(4, page);
        assertThat(GeminiPdfHousingExtractor.verifiedConversionConditionsForBase("16A", 58480000L, 233920L, pages)).isEmpty();
        var youth = GeminiPdfHousingExtractor.verifiedConversionConditionsForBase("16A", 61920000L, 247680L, pages);
        assertThat(youth).hasSize(2);
        assertThat(youth.getFirst().rentDeposit()).isEqualTo(90920000L);
    }

    @Test
    void 주차장_면적의_영을_주차_대수_영으로_해석하지_않는다() {
        var area = JSON.readTree("{\"name\":\"주차\",\"value\":\"0\",\"evidence\":\"주차장 0\"}");
        var explicitCount = JSON.readTree("{\"name\":\"주차\",\"value\":\"0대\",\"evidence\":\"주차 가능 0대\"}");
        assertThat(GeminiPdfHousingExtractor.verifiedParkingCount(0, List.of(area))).isNull();
        assertThat(GeminiPdfHousingExtractor.verifiedParkingCount(0, List.of(explicitCount))).isZero();
    }

    @Test
    void 원문_해당동_열을_호수로_착각하지_않는다() {
        String page = "형별 공급계획 공급 형별 전용면적 공가 호수 금회 모집 예비자 해당동 구조 및 난방\n31.32 0 60 206\n※ 안내";
        assertThat(GeminiPdfHousingExtractor.belongsToBuildingColumn(page, "206", "31.32 0 60 206")).isTrue();
        assertThat(GeminiPdfHousingExtractor.belongsToBuildingColumn(page, "206", "31.32 0 60 207")).isFalse();
        assertThat(GeminiPdfHousingExtractor.belongsToBuildingColumn(page.replace("해당동", "해당동 호실"), "206", "31.32 0 60 206")).isFalse();
        assertThat(GeminiPdfHousingExtractor.belongsToBuildingColumn(page, "206호", "31.32 0 60 206호")).isFalse();
    }

    @Test
    void 원문_공급표에_별도_공용면적_행이_있으면_누락을_찾는다() {
        String page = "형별 공급계획 전용 주거공용 합계 해당동\n31.32 12.60 43.92 304\n13.94 45.26 301,302\n※ 안내";
        var first = new GeminiPdfHousingExtractor.AreaVariant(new java.math.BigDecimal("31.32"), new java.math.BigDecimal("12.60"), new java.math.BigDecimal("43.92"));
        var second = new GeminiPdfHousingExtractor.AreaVariant(new java.math.BigDecimal("31.32"), new java.math.BigDecimal("13.94"), new java.math.BigDecimal("45.26"));
        assertThat(GeminiPdfHousingExtractor.missingAreaVariants(page, List.of(first))).containsExactly(second);
        assertThat(GeminiPdfHousingExtractor.missingAreaVariants(page, List.of(first, second))).isEmpty();
        assertThat(GeminiPdfHousingExtractor.missingAreaVariants(page, List.of(second))).containsExactly(first);
    }

    @Test
    void 전체_PDF에서_추출한_건물공통값과_주택값을_매핑하고_캐시한다() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> {
            calls.incrementAndGet();
            assertThat(request).contains("application/pdf", "전달된 PDF 페이지를 직접 읽으세요", "이번 응답에 전 건");
            assertThat(request).doesNotContain("PDFBox 보조 텍스트");
            return envelope(payload(true, "", 1, List.of(unit("u1", "101", 3, 1))), "STOP", 1200, 300);
        };
        GeminiPdfHousingExtractor extractor = extractor(transport, 1);
        GeminiPdfDocument document = new GeminiPdfDocument(
                "doc-1", "SH", "청년임대 모집", "공급목록.pdf", onePagePdf()
        );

        GeminiPdfExtractionResult first = extractor.extract(List.of(document));
        GeminiPdfExtractionResult cached = extractor.extract(List.of(document));

        assertThat(first.complete()).isTrue();
        assertThat(first.units()).hasSize(1);
        assertThat(first.units().getFirst().address()).isEqualTo("서울특별시 강동구 천호대로 1");
        assertThat(first.units().getFirst().elevatorAvailable()).isTrue();
        assertThat(first.units().getFirst().unitNumber()).isEqualTo("101");
        assertThat(first.units().getFirst().rawPayload()).contains("베란다", "page");
        assertThat(first.tokenUsage().totalTokens()).isEqualTo(1500);
        assertThat(cached.fromCache()).isTrue();
        assertThat(cached.apiCallCount()).isZero();
        assertThat(calls).hasValue(1);
    }

    @Test
    void V17_완료캐시가_있어도_V18은_별도키로_새로_처리한다() throws Exception {
        byte[] pdf = onePagePdf();
        String model = "gemini-3-flash-preview";
        String oldSource = "DIRECT_HOUSING_V17_RELEVANT_PAGE_COMPLETE_COLUMNS|" + model
                + "|doc-1:" + sha256(pdf) + ":1";
        GeminiPdfExtractionCache cache = new GeminiPdfExtractionCache(temporary.resolve("cache"));
        cache.write(sha256(oldSource.getBytes(StandardCharsets.UTF_8)),
                JSON.writeValueAsString(payload(true, "", 1, List.of(unit("old", "999", 1, 1)))));
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> {
            calls.incrementAndGet();
            return envelope(payload(true, "", 1, List.of(unit("new", "101", 1, 1))),
                    "STOP", 500, 100);
        };

        GeminiPdfExtractionResult result = extractor(transport, 0).extract(List.of(
                new GeminiPdfDocument("doc-1", "LH", "청년임대", "목록.pdf", pdf, 1)
        ));

        assertThat(result.fromCache()).isFalse();
        assertThat(result.units()).extracting(value -> value.unitNumber()).containsExactly("101");
        assertThat(calls).hasValue(1);
    }

    @Test
    void PDFBox는_표페이지를_선별만_하고_Gemini에는_관련페이지_PDF만_전달한다() throws Exception {
        GeminiApiTransport transport = request -> {
            JsonNode body = JSON.readTree(request);
            JsonNode parts = body.path("contents").get(0).path("parts");
            assertThat(request).contains("전달페이지매핑=1->1,2->2,3->3,4->4");
            assertThat(request).doesNotContain("PDFBox 보조 텍스트", "1 row", "2 row");
            String encodedPdf = null;
            for (JsonNode part : parts) {
                if (part.has("inlineData")) encodedPdf = part.path("inlineData").path("data").asText();
            }
            assertThat(encodedPdf).isNotBlank();
            assertThat(pdfPageCount(Base64.getDecoder().decode(encodedPdf))).isEqualTo(4);
            return envelope(payload(true, "", 1, List.of(unit("u1", "101", 3, 1))),
                    "STOP", 500, 100);
        };

        GeminiPdfExtractionResult result = extractor(transport, 0).extract(List.of(
                new GeminiPdfDocument("doc-1", "GH", "매입임대", "목록.pdf", fivePageSupplyPdf(), 1)
        ));

        assertThat(result.complete()).isTrue();
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "ZIPICK_AUDIT_LARGE_PAGE", matches = "true")
    void 실제_과천_대형페이지를_API_호출없이_텍스트로_전달한다() throws Exception {
        Path file = Path.of("tmp", "ai-investigation", "69178686.pdf");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(file));
        byte[] page;
        try (PDDocument original = org.apache.pdfbox.Loader.loadPDF(file.toFile());
             PDDocument selected = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            selected.importPage(original.getPage(5)); selected.save(output); page = output.toByteArray();
        }
        assertThat(page.length).isGreaterThan(40 * 1024 * 1024);
        Path response = Path.of("tmp", "ai-investigation", "gwachon-large-page", "cache", "responses",
                "2e3448e0e8ae7f443ec73bc95b70673ebfe21d71e90690a8164a5a7b276d0301.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(response));
        @SuppressWarnings("unchecked") Map<String, Object> savedResponse = JSON.readValue(
                java.nio.file.Files.readString(response), Map.class);
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> {
            calls.incrementAndGet();
            assertThat(request).contains("16A", "382", "이미지와 도면은 제공되지 않았으므로");
            assertThat(JSON.readTree(request).path("contents").get(0).path("parts").toString()).doesNotContain("inlineData");
            return envelope(savedResponse, "STOP", 500, 100);
        };
        var result = extractor(transport, 0, 40 * 1024 * 1024L, 50).extract(List.of(
                new GeminiPdfDocument("LH_FILE:69178686:page6", "LH", "과천", "팸플릿.pdf", page)));
        assertThat(calls).hasValue(1);
        assertThat(result.warnings()).anyMatch(w -> w.contains("이미지 미분석"));
        assertThat(result.units()).hasSize(7);
        assertThat(result.units()).allSatisfy(unit -> {
            assertThat(unit.exclusiveArea()).isNull();
            assertThat(unit.supplyCount()).isNull();
        });
    }

    @Test
    void 한페이지가_크면_텍스트만_전달하고_이미지_미분석_기록을_캐시에_남긴다() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(); document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700); content.showText("Housing 101"); content.endText();
            }
            document.save(output); pdf = output.toByteArray();
        }
        long limit = subsetSize(pdf, List.of(1)) - 1L;
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> {
            calls.incrementAndGet();
            assertThat(request).contains("Housing 101", "이미지와 도면은 제공되지 않았으므로");
            assertThat(JSON.readTree(request).path("contents").get(0).path("parts").toString()).doesNotContain("inlineData");
            return envelope(payload(true, "", 1, List.of(unit("u1", "101", 1, 1))), "STOP", 500, 100);
        };
        var extractor = extractor(transport, 0, limit);
        var doc = new GeminiPdfDocument("doc-1", "LH", "공고", "공고.pdf", pdf, 1);
        var result = extractor.extract(List.of(doc));
        assertThat(result.complete()).isTrue();
        assertThat(result.warnings()).anyMatch(w -> w.contains("이미지 미분석") && w.contains("page=1"));
        assertThat(result.units().getFirst().rawPayload()).contains("이미지 미분석 페이지");
        var cached = extractor.extract(List.of(doc));
        assertThat(cached.fromCache()).isTrue();
        assertThat(cached.warnings()).anyMatch(w -> w.contains("이미지 미분석"));
        assertThat(calls).hasValue(1);
    }

    @Test
    void 대형페이지에_추출할_텍스트도_없으면_성공으로_처리하지_않는다() throws Exception {
        byte[] pdf = onePagePdf();
        long limit = subsetSize(pdf, List.of(1)) - 1L;
        var extractor = extractor(request -> { throw new AssertionError("API 호출 금지"); }, 0, limit);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> extractor.extract(List.of(
                new GeminiPdfDocument("doc-1", "LH", "공고", "공고.pdf", pdf, 1))))
                .hasMessageContaining("추출 가능한 텍스트 없음");
    }

    @Test
    void 팸플릿_분할응답의_재사용된_행번호는_서로_다른_주택형을_덮어쓰지_않는다() throws Exception {
        byte[] pdf = blankPagePdf(2);
        long limit = (subsetSize(pdf, List.of(1, 2)) + subsetSize(pdf, List.of(1))) / 2L;
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> {
            int call = calls.getAndIncrement();
            var first = unit("u1", "101", call + 1, 1);
            var second = unit("u2", "101", call + 1, 2);
            first.put("unitNumber", ""); second.put("unitNumber", "");
            first.put("housingType", call == 0 ? "16A" : "16B");
            second.put("housingType", call == 0 ? "16B" : "26A");
            first.put("supplyType", "행복주택"); second.put("supplyType", "행복주택");
            Map<String, Object> response = new java.util.LinkedHashMap<>(payload(call > 0, "", 3, List.of(first, second)));
            response.put("documents", List.of(Map.of("documentId", "doc-1", "documentType", "입주자 팸플릿",
                    "relevantPages", List.of(call + 1), "expectedUnitCount", 3)));
            return envelope(response, "STOP", 500, 100);
        };
        var result = extractor(transport, 0, limit, 50).extract(List.of(
                new GeminiPdfDocument("doc-1", "LH", "공고", "팸플릿.pdf", pdf, 3)));
        assertThat(calls).hasValue(2);
        assertThat(result.complete()).isTrue();
        assertThat(result.units()).extracting(DetailedHousingUnit::housingType)
                .containsExactly("16A", "16B", "26A");
    }

    @Test
    void 원본이_크기한도를_넘으면_페이지묶음으로_나눠_병합한다() throws Exception {
        byte[] compact = blankPagePdf(8);
        byte[] original = compact;
        int fullSubsetSize = subsetSize(original, java.util.stream.IntStream.rangeClosed(1, 8).boxed().toList());
        int largestSinglePage = java.util.stream.IntStream.rangeClosed(1, 8)
                .map(page -> subsetSize(original, List.of(page))).max().orElseThrow();
        long requestLimit = (fullSubsetSize + largestSinglePage) / 2L;
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> {
            calls.incrementAndGet();
            assertThat(request).contains("페이지 분할 분석");
            JsonNode parts = JSON.readTree(request).path("contents").get(0).path("parts");
            String encodedPdf = null;
            for (JsonNode part : parts) {
                if (part.has("inlineData")) encodedPdf = part.path("inlineData").path("data").asText();
            }
            byte[] selected = Base64.getDecoder().decode(encodedPdf);
            assertThat(selected.length).isLessThanOrEqualTo((int) requestLimit);
            return envelope(payload(true, "", 1, List.of(unit("u1", "101", 3, 1))),
                    "STOP", 500, 100);
        };

        GeminiPdfExtractionResult result = extractor(transport, 0, requestLimit).extract(List.of(
                new GeminiPdfDocument("doc-1", "LH", "대형 팸플릿", "대형.pdf", original, 1)
        ));

        assertThat(result.complete()).isTrue();
        assertThat(result.apiCallCount()).isGreaterThan(1);
        assertThat(calls).hasValue(result.apiCallCount());
    }

    @Test
    void 응답_스키마를_평면화하고_정확한_행수는_서버에서_검증한다() throws Exception {
        GeminiApiTransport transport = request -> {
            var body = JSON.readTree(request);
            var properties = body.path("generationConfig").path("responseJsonSchema").path("properties");
            var unitProperties = properties.path("units").path("items").path("properties");
            assertThat(properties.has("rentalConditions")).isTrue();
            assertThat(properties.has("additionalAttributes")).isTrue();
            assertThat(unitProperties.has("rentalConditions")).isFalse();
            assertThat(unitProperties.has("additionalAttributes")).isFalse();
            assertThat(properties.path("units").path("items").path("required").toString())
                    .contains("complexName", "exclusiveArea", "rentDeposit", "monthlyRent", "housingType");
            assertThat(unitProperties.path("exclusiveArea").path("nullable").asBoolean()).isTrue();
            assertThat(unitProperties.has("sourceTableId")).isTrue();
            assertThat(unitProperties.has("verifiedNullFields")).isTrue();
            assertThat(properties.path("units").has("minItems")).isFalse();
            assertThat(properties.path("units").has("maxItems")).isFalse();
            assertThat(request).contains(
                    "공급행수=3(서버가 확인한 최소 후보 수)",
                    "첫 행부터 마지막 행까지 모두 반환하세요",
                    "모두 최상위 배열입니다",
                    "베란다",
                    "주차 방식"
            );
            return envelope(flatPayload(true, "", 3, List.of(
                    unit("u1", "101", 3, 1),
                    unit("u2", "102", 3, 2),
                    unit("u3", "103", 3, 3)
            )), "STOP", 800, 600);
        };

        GeminiPdfExtractionResult result = extractor(transport, 0).extract(List.of(
                new GeminiPdfDocument(
                        "doc-1", "GH", "매입임대", "목록.pdf", onePagePdf(), 3
                )
        ));

        assertThat(result.complete()).isTrue();
        assertThat(result.units()).hasSize(3);
        assertThat(result.units().getFirst().rawPayload())
                .contains("documentAdditionalAttributes", "buildingAdditionalAttributes", "unitAdditionalAttributes",
                        "공용면적", "6.885", "임대면적", "51.305");
        assertThat(result.units().getFirst().rentalConditions()).hasSize(1);
        assertThat(result.units().getFirst().rawPayload()).contains("베란다", "옥상출입", "온라인");
    }

    @Test
    void 공급표_연번만_행수로_인정하고_일반_목차번호는_제외한다() {
        GeminiPdfHousingExtractor extractor = extractor(request -> "", 0);

        assertThat(extractor.estimateUnitCount("""
                연번 주소 전용면적 호수 임대료
                1 서울특별시 39.5 101 250000
                2 서울특별시 40.1 102 260000
                3 서울특별시 41.0 103 270000
                """)).isEqualTo(3);
        assertThat(extractor.estimateUnitCount("""
                순번 명 주소 면적 임대보증금
                1 A주택 서울특별시 39.5 10000000
                2 B주택 서울특별시 40.1 12000000
                """)).isEqualTo(2);
        assertThat(extractor.estimateUnitCount("""
                1. 공급 개요
                2. 신청 자격
                3. 신청 방법
                주소 면적 호수 임대료
                """)).isNull();
        assertThat(extractor.estimateUnitCount("""
                예비입주자 순번 발표
                1. 모집 일정
                2. 신청 자격
                3. 신청 방법
                공급대상 주택의 주소, 면적, 호수와 임대조건은 웹사이트에서 확인
                4. 서류 제출
                5. 계약
                6. 입주
                7. 유의사항
                """)).isNull();
        assertThat(extractor.estimateUnitCount("""
                순번 주소 면적 임대료
                건물의 기본 정보는 아래를 확인하세요
                1. 신청 자격
                2. 신청 서류
                3. 소득 기준
                """)).isNull();
    }

    @Test
    void 실제_예비입주자공고의_다른번호를_공급행으로_오인하지_않는다() throws Exception {
        Path directory = Path.of("tmp", "ai-investigation");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(directory.resolve("69173002.pdf")));
        GeminiPdfHousingExtractor extractor = extractor(request -> "", 0);
        for (String id : List.of("69173002", "65965106", "69165378")) {
            Path pdf = directory.resolve(id + ".pdf");
            if (!java.nio.file.Files.exists(pdf)) continue;
            try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
                var stripper = new org.apache.pdfbox.text.PDFTextStripper();
                stripper.setSortByPosition(true);
                assertThat(extractor.estimateUnitCount(stripper.getText(document))).as(id).isNull();
            }
        }
    }

    @Test
    void 실제_단일주택공고의_신청안내번호를_누락된_표페이지로_오인하지_않는다() throws Exception {
        Path pdf = Path.of("tmp", "ai-investigation", "69173002.pdf");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(pdf));
        GeminiApiTransport transport = request -> envelope(payload(true, "", 1,
                List.of(unit("u1", "101", 1, 1))), "STOP", 500, 100);
        GeminiPdfExtractionResult result = extractor(transport, 0).extract(List.of(
                new GeminiPdfDocument("doc-1", "LH", "예비입주자 공고", "공고문.pdf",
                        java.nio.file.Files.readAllBytes(pdf), 1)));
        assertThat(result.complete()).isTrue();
        assertThat(result.warnings()).noneMatch(value -> value.contains("공급표 원문 페이지 누락"));
    }

    @Test
    void 실제_SH공고의_주택표가_있는_2페이지를_반드시_모델에_전달한다() throws Exception {
        Path pdf = Path.of("tmp", "ai-investigation", "SH-306301-2.pdf");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(pdf));
        GeminiApiTransport transport = request -> {
            assertThat(request).containsPattern("전달페이지매핑=[^\\\"]*2->2(?:,|\\\")");
            return envelope(payload(true, "", 1, List.of(unit("u1", "402", 2, 1))), "STOP", 500, 100);
        };
        extractor(transport, 0).extract(List.of(new GeminiPdfDocument("doc-1", "SH", "공고", "공고문.pdf",
                java.nio.file.Files.readAllBytes(pdf))));
    }

    @Test
    void 실제_임대금액표가_있는데_금액이_전부_빠지면_보정한다() throws Exception {
        Path pdf = Path.of("tmp", "ai-investigation", "69178684.pdf");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(pdf));
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> {
            Map<String, Object> row = unit("u1", "101", 3, 1);
            Map<String, Object> response = new java.util.LinkedHashMap<>(payload(true, "", 1, List.of(row)));
            response.put("rentalConditions", List.of());
            if (calls.getAndIncrement() == 0) {
                row.remove("rentDeposit");
                row.remove("monthlyRent");
                response = new java.util.LinkedHashMap<>(payload(true, "", 1, List.of(row)));
                response.put("rentalConditions", List.of());
            } else {
                assertThat(request).contains("missingPricingIndexes", "원문 임대보증금");
            }
            return envelope(response, "STOP", 500, 100);
        };
        var result = extractor(transport, 1, 20 * 1024 * 1024L, 50).extract(List.of(
                new GeminiPdfDocument("doc-1", "LH", "공고", "공고문.pdf", java.nio.file.Files.readAllBytes(pdf))));
        assertThat(result.complete()).isTrue();
        assertThat(calls).hasValue(2);
        assertThat(result.units().getFirst().rentDeposit()).isEqualTo(10_000_000L);
    }

    @Test
    void 같은_주택형의_공급대상은_원문_행_표시로_구분하여_금액을_보정한다() throws Exception {
        Path pdf = Path.of("tmp", "ai-investigation", "69178684.pdf");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(pdf));
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> {
            boolean repair = calls.getAndIncrement() > 0;
            Map<String, Object> first = unit("u1", "101", 3, 1);
            Map<String, Object> second = unit("u2", "101", 3, 2);
            first.put("sourceRowLabel", "16A 대학생 우선공급");
            second.put("sourceRowLabel", "16A 대학생 일반공급");
            if (!repair) {
                first.remove("rentDeposit"); first.remove("monthlyRent");
                second.remove("rentDeposit"); second.remove("monthlyRent");
            } else {
                first.put("rentDeposit", 58480000L);
                second.put("rentDeposit", 61920000L);
            }
            Map<String, Object> response = new java.util.LinkedHashMap<>(payload(true, "", 2, List.of(first, second)));
            response.put("rentalConditions", List.of());
            return envelope(response, "STOP", 500, 100);
        };
        var result = extractor(transport, 1, 20 * 1024 * 1024L, 50).extract(List.of(
                new GeminiPdfDocument("doc-1", "LH", "공고", "공고문.pdf", java.nio.file.Files.readAllBytes(pdf))));
        assertThat(calls).hasValue(2);
        assertThat(result.complete()).isTrue();
        assertThat(result.units()).extracting(unit -> unit.rentDeposit())
                .containsExactly(58480000L, 61920000L);
    }

    @Test
    void 금액_보정에서_다른_주택이_같은_순번으로_반환되면_기존_행을_보존한다() throws Exception {
        Path pdf = Path.of("tmp", "ai-investigation", "69178684.pdf");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(pdf));
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> {
            Map<String, Object> row = unit("u1", calls.getAndIncrement() == 0 ? "101" : "202", 3, 1);
            if (calls.get() == 1) {
                row.remove("rentDeposit");
                row.remove("monthlyRent");
            }
            Map<String, Object> response = new java.util.LinkedHashMap<>(payload(true, "", 1, List.of(row)));
            response.put("rentalConditions", List.of());
            return envelope(response, "STOP", 500, 100);
        };
        var result = extractor(transport, 1, 20 * 1024 * 1024L, 50).extract(List.of(
                new GeminiPdfDocument("doc-1", "LH", "공고", "공고문.pdf", java.nio.file.Files.readAllBytes(pdf))));
        assertThat(calls).hasValue(2);
        assertThat(result.complete()).isFalse();
        assertThat(result.units().getFirst().unitNumber()).isEqualTo("101");
        assertThat(result.units().getFirst().rentDeposit()).isNull();
        assertThat(result.units().getFirst().exclusiveArea()).isNotNull();
    }

    @Test
    void 주택별_임대조건을_개별안내하는_공고는_전환예시를_실제금액으로_취급하지_않는다() throws Exception {
        for (String id : List.of("69107811", "69108015")) {
            Path pdf = Path.of("tmp", "ai-investigation", id + ".pdf");
            org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(pdf));
            AtomicInteger calls = new AtomicInteger();
            GeminiApiTransport transport = request -> {
                calls.incrementAndGet();
                Map<String, Object> row = unit("u1", "101", 2, 1);
                row.remove("rentDeposit"); row.remove("monthlyRent");
                Map<String, Object> response = new java.util.LinkedHashMap<>(payload(true, "", 1, List.of(row)));
                response.put("rentalConditions", List.of());
                return envelope(response, "STOP", 500, 100);
            };
            var result = extractor(transport, 1, 20 * 1024 * 1024L, 50).extract(List.of(
                    new GeminiPdfDocument("doc-1", "LH", "공고", id + ".pdf", java.nio.file.Files.readAllBytes(pdf))));
            assertThat(calls).hasValue(1);
            assertThat(result.complete()).isTrue();
            assertThat(result.units().getFirst().rentDeposit()).isNull();
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "ZIPICK_AUDIT_REAL_PDFS", matches = "true")
    void 실제_미사용문서들의_공급행_추정치를_점검한다() throws Exception {
        Path directory = Path.of("tmp", "ai-investigation");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isDirectory(directory));
        GeminiPdfHousingExtractor extractor = extractor(request -> "", 0);
        try (var files = java.nio.file.Files.list(directory)) {
            for (Path pdf : files.filter(path -> path.toString().endsWith(".pdf")).sorted().toList()) {
                try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
                    var stripper = new org.apache.pdfbox.text.PDFTextStripper();
                    stripper.setSortByPosition(true);
                    Integer count = extractor.estimateUnitCount(stripper.getText(document));
                    System.out.println("SUPPLY_AUDIT file=" + pdf.getFileName() + " pages="
                            + document.getNumberOfPages() + " count=" + count);
                }
            }
        }
    }

    @Test
    void 모델이_이전_행_주소를_반복해도_현재_행_근거에서_도로명주소를_복구한다() {
        GeminiPdfHousingExtractor extractor = extractor(request -> "", 0);

        assertThat(extractor.roadAddressFromEvidence(
                "광주시", "8 광주시 능평로30번길 60 능평리 499 삼성지오빌C동 302"
        )).isEqualTo("광주시 능평로30번길 60");
    }

    @Test
    void 출력이_잘리면_같은_PDF와_커서로_후속_호출하고_중복없이_합친다() throws Exception {
        Queue<String> responses = new ArrayDeque<>();
        responses.add(envelope(payload(false, "doc-1/item-1", 2, List.of(unit("u1", "101", 3, 1))),
                "MAX_TOKENS", 1000, 450));
        responses.add(envelope(payload(true, "", 2, List.of(unit("u2", "102", 4, 2))),
                "STOP", 1000, 300));
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> {
            if (calls.getAndIncrement() == 1) {
                assertThat(request).contains("missingExtractionIndexes", "[2]", "1건");
                var unitsSchema = JSON.readTree(request).path("generationConfig").path("responseJsonSchema")
                        .path("properties").path("units");
                assertThat(unitsSchema.has("minItems")).isFalse();
                assertThat(unitsSchema.has("maxItems")).isFalse();
            }
            return responses.remove();
        };

        GeminiPdfExtractionResult result = extractor(transport, 2).extract(List.of(
                new GeminiPdfDocument("doc-1", "GH", "매입임대", "목록.pdf", onePagePdf(), 2)
        ));

        assertThat(result.complete()).isTrue();
        assertThat(result.apiCallCount()).isEqualTo(2);
        assertThat(result.units()).extracting(value -> value.unitNumber()).containsExactly("101", "102");
        assertThat(result.tokenUsage().totalTokens()).isEqualTo(2_750);
    }

    @Test
    void 잘린_JSON은_페이지를_분할해_다시_분석한다() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> switch (calls.getAndIncrement()) {
            case 0 -> JSON.writeValueAsString(Map.of("candidates", List.of(Map.of(
                    "finishReason", "MAX_TOKENS", "content", Map.of("parts", List.of(Map.of("text", "{\"units\":[")))))));
            case 1 -> envelope(payload(false, "", 2, List.of(unit("u1", "101", 1, 1))), "STOP", 100, 50);
            default -> envelope(payload(true, "", 2, List.of(unit("u2", "102", 2, 2))), "STOP", 100, 50);
        };
        GeminiPdfExtractionResult result = extractor(transport, 2).extract(List.of(
                new GeminiPdfDocument("doc-1", "GH", "목록", "목록.pdf", blankPagePdf(2), 2)));
        assertThat(result.complete()).isTrue();
        assertThat(result.apiCallCount()).isEqualTo(3);
        assertThat(result.units()).extracting(DetailedHousingUnit::unitNumber).containsExactly("101", "102");
    }

    @Test
    void 연번만_채웠어도_공급행이_있는_마지막페이지가_빠지면_완료로_처리하지_않는다() throws Exception {
        Queue<String> responses = new ArrayDeque<>();
        responses.add(envelope(payload(true, "", 2, List.of(
                unit("u1", "101", 1, 1),
                unit("u2-wrong", "102", 1, 2)
        )), "STOP", 500, 200));
        responses.add(envelope(payload(true, "", 2, List.of(
                unit("u2-correct", "202", 2, 2)
        )), "STOP", 300, 100));
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> {
            int call = calls.getAndIncrement();
            if (call == 1) assertThat(request).contains("missingSourcePages", "[2]");
            return responses.remove();
        };

        GeminiPdfExtractionResult result = extractor(transport, 1).extract(List.of(
                new GeminiPdfDocument("doc-1", "GH", "매입임대", "목록.pdf", twoPageRowPdf(), 2)
        ));

        assertThat(result.complete()).isTrue();
        assertThat(result.apiCallCount()).isEqualTo(2);
        assertThat(result.units()).extracting(value -> value.unitNumber()).containsExactly("101", "202");
        assertThat(calls).hasValue(2);
    }

    @Test
    void AI가_완료라고_해도_서버가_누락_순번을_검증해_보정_호출한다() throws Exception {
        Queue<String> responses = new ArrayDeque<>();
        responses.add(envelope(payload(true, "", 4, List.of(
                unit("u1", "101", 1, 1), unit("u4", "104", 1, 4)
        )), "STOP", 1000, 300));
        responses.add(envelope(payload(true, "", 4, List.of(
                unit("u2", "102", 1, 2), unit("u3", "103", 1, 3)
        )), "STOP", 1000, 300));
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> {
            if (calls.getAndIncrement() == 1) {
                assertThat(request).contains("missingExtractionIndexes", "[2, 3]", "서버가 이전 응답");
            }
            return responses.remove();
        };

        GeminiPdfExtractionResult result = extractor(transport, 2).extract(List.of(
                new GeminiPdfDocument("doc-1", "GH", "매입임대", "목록.pdf", onePagePdf())
        ));

        assertThat(result.complete()).isTrue();
        assertThat(result.apiCallCount()).isEqualTo(2);
        assertThat(result.units()).extracting(value -> value.sourceRowNumber())
                .containsExactly(1, 4, 2, 3);
    }

    @Test
    void 모델이_행수를_축소해도_서버가_확인한_행수로_누락을_보정한다() throws Exception {
        Queue<String> responses = new ArrayDeque<>();
        responses.add(envelope(payload(true, "", 2, List.of(
                unit("u1", "101", 1, 1), unit("u2", "102", 1, 2)
        )), "STOP", 1000, 300));
        responses.add(envelope(payload(true, "", 4, List.of(
                unit("u3", "103", 1, 3), unit("u4", "104", 1, 4)
        )), "STOP", 1000, 300));
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> {
            if (calls.getAndIncrement() == 1) {
                assertThat(request).contains("missingExtractionIndexes", "[3, 4]");
            }
            return responses.remove();
        };

        GeminiPdfExtractionResult result = extractor(transport, 1).extract(List.of(
                new GeminiPdfDocument("doc-1", "GH", "매입임대", "목록.pdf", onePagePdf(), 4)
        ));

        assertThat(result.complete()).isTrue();
        assertThat(result.units()).hasSize(4);
        assertThat(result.apiCallCount()).isEqualTo(2);
    }

    @Test
    void 기존_파서가_한행만_읽어도_AI가_확인한_더_큰_연속행수를_검증기준으로_사용한다() throws Exception {
        GeminiApiTransport transport = request -> envelope(payload(true, "", 3, List.of(
                unit("u1", "101", 1, 1),
                unit("u2", "102", 1, 2),
                unit("u3", "201", 1, 3)
        )), "STOP", 1000, 300);

        GeminiPdfExtractionResult result = extractor(transport, 0).extract(List.of(
                new GeminiPdfDocument("doc-1", "SH", "청년임대", "목록.pdf", onePagePdf(), 1)
        ));

        assertThat(result.complete()).isTrue();
        assertThat(result.expectedUnitCount()).isEqualTo(3);
        assertThat(result.units()).hasSize(3);
    }

    @Test
    void 같은_공급표의_선택열_일부가_비어도_일반문서는_재호출하지_않는다() throws Exception {
        Map<String, Object> incomplete = unit("u2", "102", 1, 2);
        incomplete.put("rentDeposit", null);
        incomplete.put("monthlyRent", null);
        incomplete.put("exclusiveArea", null);
        GeminiApiTransport transport = request -> envelope(payload(true, "", 2, List.of(
                unit("u1", "101", 1, 1), incomplete
        )), "STOP", 1000, 200);
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport counted = request -> {
            calls.incrementAndGet();
            return transport.generate(request);
        };

        GeminiPdfExtractionResult result = extractor(counted, 1).extract(List.of(
                new GeminiPdfDocument("doc-1", "SH", "청년임대", "목록.pdf", onePagePdf(), 2)
        ));

        assertThat(result.complete()).isTrue();
        assertThat(result.units()).hasSize(2);
        assertThat(result.units().get(1).rentDeposit()).isNull();
        assertThat(result.warnings()).anyMatch(value -> value.contains("선택 필드 일부 누락"));
        assertThat(result.apiCallCount()).isEqualTo(1);
        assertThat(calls).hasValue(1);
    }

    @Test
    void 다중_임대조건_일부가_빠져도_선택정보로_경고만_남긴다() throws Exception {
        Map<String, Object> first = new java.util.LinkedHashMap<>(payload(true, "", 2, List.of(
                unit("u1", "101", 1, 1), unit("u2", "102", 1, 2)
        )));
        first.put("rentalConditions", List.of(
                condition("u1", "COND_1", "청년 1순위", 1_000_000, 170_000),
                condition("u1", "COND_2", "청년 2순위, 3순위", 2_000_000, 206_000),
                condition("u1", "2", "청년 2·3순위", 2_000_000, 206_000)
        ));
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> {
            calls.incrementAndGet();
            return envelope(first, "STOP", 1000, 500);
        };

        GeminiPdfExtractionResult result = extractor(transport, 1).extract(List.of(
                new GeminiPdfDocument("doc-1", "LH", "청년 매입임대", "목록.pdf", onePagePdf(), 2)
        ));

        assertThat(result.complete()).isTrue();
        assertThat(result.apiCallCount()).isEqualTo(1);
        assertThat(result.units()).hasSize(2);
        assertThat(result.units().getFirst().rentalConditions()).hasSize(2);
        assertThat(result.units().get(1).rentalConditions()).isEmpty();
        assertThat(result.warnings()).anyMatch(value -> value.contains("선택 필드 일부 누락"));
        assertThat(calls).hasValue(1);
    }

    @Test
    void 원문에서_확인된_공란은_누락이_아닌_검증된_NULL로_인정한다() throws Exception {
        Map<String, Object> sourceEmpty = unit("u2", "102", 1, 2);
        sourceEmpty.put("rentDeposit", null);
        sourceEmpty.put("monthlyRent", null);
        sourceEmpty.put("evidence", "보증금 - 월임대료 -");
        sourceEmpty.put("verifiedNullFields", List.of("rentDeposit", "monthlyRent"));
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> {
            calls.incrementAndGet();
            assertThat(request).contains("verifiedNullFields", "값을 찾지 못했다는 이유");
            return envelope(payload(true, "", 2, List.of(
                    unit("u1", "101", 1, 1), sourceEmpty
            )), "STOP", 1000, 300);
        };

        GeminiPdfExtractionResult result = extractor(transport, 1).extract(List.of(
                new GeminiPdfDocument("doc-1", "SH", "청년임대", "목록.pdf", onePagePdf(), 2)
        ));

        assertThat(result.complete()).isTrue();
        assertThat(result.apiCallCount()).isEqualTo(1);
        assertThat(result.units().get(1).rentDeposit()).isNull();
        assertThat(result.units().get(1).rawPayload()).contains("verifiedNullFields", "rentDeposit");
        assertThat(calls).hasValue(1);
    }

    @Test
    void 근거없는_verifiedNull은_인정하지_않되_선택필드라면_재호출하지_않는다() throws Exception {
        Map<String, Object> falseNull = unit("u2", "102", 1, 2);
        falseNull.put("unitNumber", null);
        falseNull.put("evidence", "서울특별시 강동구 공급주택");
        falseNull.put("verifiedNullFields", List.of("unitNumber"));
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> {
            calls.incrementAndGet();
            return envelope(payload(true, "", 2, List.of(
                unit("u1", "101", 1, 1), falseNull
            )), "STOP", 500, 100);
        };

        GeminiPdfExtractionResult result = extractor(transport, 1).extract(List.of(
                new GeminiPdfDocument("doc-1", "LH", "청년임대", "목록.pdf", onePagePdf(), 2)
        ));

        assertThat(result.complete()).isTrue();
        assertThat(result.apiCallCount()).isEqualTo(1);
        assertThat(result.units().get(1).unitNumber()).isNull();
        assertThat(result.warnings()).anyMatch(value -> value.contains("선택 필드 일부 누락"));
        assertThat(calls).hasValue(1);
    }

    @Test
    void 서로_다른_공급표의_서로_다른_열은_누락으로_오판하지_않는다() throws Exception {
        Map<String, Object> firstTable = unit("u1", "101", 1, 1);
        Map<String, Object> secondTable = unit("u2", "102", 2, 2);
        firstTable.put("sourceTableId", "doc-1/table-1");
        secondTable.put("sourceTableId", "doc-1/table-2");
        secondTable.put("rentDeposit", null);
        secondTable.put("monthlyRent", null);
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> {
            calls.incrementAndGet();
            return envelope(payload(true, "", 2, List.of(firstTable, secondTable)),
                    "STOP", 1000, 300);
        };

        GeminiPdfExtractionResult result = extractor(transport, 1).extract(List.of(
                new GeminiPdfDocument("doc-1", "GH", "임대공고", "목록.pdf", onePagePdf(), 2)
        ));

        assertThat(result.complete()).isTrue();
        assertThat(result.apiCallCount()).isEqualTo(1);
        assertThat(calls).hasValue(1);
    }

    @Test
    void 대형문서의_누락행을_40건씩_나누어_전부_보정한다() throws Exception {
        Queue<String> responses = new ArrayDeque<>();
        responses.add(envelope(payload(true, "", 95, List.of(
                unit("u1", "101", 1, 1), unit("u95", "195", 4, 95)
        )), "STOP", 1000, 300));
        responses.add(envelope(payload(true, "", 95, units(2, 41)), "STOP", 1000, 5000));
        responses.add(envelope(payload(true, "", 95, units(42, 81)), "STOP", 1000, 5000));
        responses.add(envelope(payload(true, "", 95, units(82, 94)), "STOP", 1000, 2000));
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> {
            int call = calls.getAndIncrement();
            if (call == 1) {
                assertThat(request).contains("missingExtractionIndexes", "2", "41");
                var unitsSchema = JSON.readTree(request).path("generationConfig").path("responseJsonSchema")
                        .path("properties").path("units");
                assertThat(unitsSchema.has("minItems")).isFalse();
                assertThat(unitsSchema.has("maxItems")).isFalse();
            }
            if (call == 2) assertThat(request).contains("missingExtractionIndexes", "42", "81");
            if (call == 3) assertThat(request).contains("missingExtractionIndexes", "82", "94");
            return responses.remove();
        };

        GeminiPdfExtractionResult result = extractor(transport, 3).extract(List.of(
                new GeminiPdfDocument("doc-1", "LH", "청년 매입임대", "목록.pdf", onePagePdf(), 95)
        ));

        assertThat(result.complete()).isTrue();
        assertThat(result.units()).hasSize(95);
        assertThat(result.apiCallCount()).isEqualTo(4);
        assertThat(calls).hasValue(4);
    }

    @Test
    void 추출_일시_오류_후_성공은_최종_호출_실패를_남기지_않는다() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> {
            if (calls.getAndIncrement() == 0) throw new IllegalStateException("status=503");
            return envelope(payload(true, "", 1, List.of(unit("u1", "101", 1, 1))), "STOP", 500, 100);
        };
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(GeminiFinalFailureLogger.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            extractor(transport, 1).extract(List.of(
                    new GeminiPdfDocument("doc-retry", "GH", "매입임대", "목록.pdf", onePagePdf(), 1)));
            assertThat(calls.get()).isGreaterThanOrEqualTo(2);
            assertThat(appender.list).isEmpty();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void 일시장애가_발생하면_성공한_부분행을_저장하고_다음실행에서_이어간다() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> switch (calls.getAndIncrement()) {
            case 0 -> envelope(payload(true, "", 3, List.of(
                    unit("u1", "101", 1, 1)
            )), "STOP", 500, 100);
            case 1 -> envelope(payload(true, "", 3, List.of(
                    unit("u2", "102", 1, 2)
            )), "STOP", 500, 100);
            case 2, 3, 4 -> throw new IllegalStateException("Gemini PDF 분석 실패: status=503 HIGH DEMAND");
            default -> {
                assertThat(request).contains("missingExtractionIndexes", "[3]");
                yield envelope(payload(true, "", 3, List.of(
                        unit("u3", "103", 1, 3)
                )), "STOP", 500, 100);
            }
        };
        GeminiPdfHousingExtractor extractor = extractor(transport, 3);
        GeminiPdfDocument document = new GeminiPdfDocument(
                "doc-1", "GH", "매입임대", "목록.pdf", onePagePdf(), 3);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(GeminiFinalFailureLogger.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> extractor.extract(List.of(document)))
                    .hasMessageContaining("status=503");
            assertThat(appender.list).singleElement().satisfies(event ->
                    assertThat(event.getFormattedMessage()).contains("stage=extraction", "status=503"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        GeminiPdfExtractionResult resumed = extractor.extract(List.of(document));

        assertThat(resumed.complete()).isTrue();
        assertThat(resumed.units()).hasSize(3);
        assertThat(resumed.apiCallCount()).isEqualTo(1);
        assertThat(calls).hasValue(6);
    }

    @Test
    void 잘린_JSON_응답은_원인을_알_수_있게_실패한다() throws Exception {
        GeminiApiTransport transport = request -> """
                {"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"{"}]}}],
                 "usageMetadata":{"promptTokenCount":1000,"candidatesTokenCount":49152,"totalTokenCount":50152}}
                """;

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> extractor(transport, 1).extract(List.of(
                        new GeminiPdfDocument("doc-1", "GH", "매입임대", "목록.pdf", onePagePdf())
                )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JSON이 잘렸거나", "MAX_TOKENS");
    }

    @Test
    void 호실별_승강기값은_건물공통값보다_우선한다() throws Exception {
        GeminiApiTransport transport = request -> envelope(
                payload(true, "", 1, List.of(unitWithElevator("u1", "101", 3, 1, false))),
                "STOP", 500, 100
        );

        GeminiPdfExtractionResult result = extractor(transport, 0).extract(List.of(
                new GeminiPdfDocument("doc-1", "GH", "매입임대", "목록.pdf", onePagePdf())
        ));

        assertThat(result.units()).hasSize(1);
        assertThat(result.units().getFirst().elevatorAvailable()).isFalse();
    }

    @Test
    void 대표_임대료가_없으면_첫번째_조건별_임대료를_사용한다() throws Exception {
        Map<String, Object> unit = unit("u1", "101", 3, 1);
        unit.remove("rentDeposit");
        unit.remove("monthlyRent");
        unit.put("rentalConditions", List.of(Map.of(
                "conditionCode", "BASE", "conditionName", "기본",
                "rentDeposit", 12_000_000, "monthlyRent", 320_000,
                "page", 3, "evidence", "보증금 12,000,000 월세 320,000"
        )));
        GeminiApiTransport transport = request -> envelope(
                payload(true, "", 1, List.of(unit)), "STOP", 500, 100
        );

        GeminiPdfExtractionResult result = extractor(transport, 0).extract(List.of(
                new GeminiPdfDocument("doc-1", "SH", "청년임대", "목록.pdf", onePagePdf())
        ));

        assertThat(result.units().getFirst().rentDeposit()).isEqualTo(12_000_000L);
        assertThat(result.units().getFirst().monthlyRent()).isEqualTo(320_000L);
    }

    @Test
    void 보정응답에서_building이_빠져도_unit_자체값으로_복구한다() throws Exception {
        Map<String, Object> selfContained = unit("u1", "101", 1, 1);
        selfContained.put("complexName", "자체완결주택");
        selfContained.put("provinceName", "경기도");
        selfContained.put("districtName", "고양시 덕양구");
        selfContained.put("roadAddress", "고양시 덕양구 테스트로 1");
        selfContained.put("housingType", "다세대");
        Map<String, Object> response = new java.util.LinkedHashMap<>(payload(true, "", 1, List.of(selfContained)));
        response.put("buildings", List.of());
        GeminiApiTransport transport = request -> envelope(response, "STOP", 500, 100);

        GeminiPdfExtractionResult result = extractor(transport, 0).extract(List.of(
                new GeminiPdfDocument("doc-1", "GH", "매입임대", "목록.pdf", onePagePdf(), 1)
        ));

        assertThat(result.complete()).isTrue();
        assertThat(result.units()).hasSize(1);
        assertThat(result.units().getFirst().complexName()).isEqualTo("자체완결주택");
        assertThat(result.units().getFirst().housingType()).isEqualTo("다세대");
        assertThat(result.units().getFirst().address()).isEqualTo("고양시 덕양구 테스트로 1");
    }

    @Test
    void 일반주소에_지번이_이어져도_저장주소는_완전한_도로명주소만_사용한다() throws Exception {
        Map<String, Object> response = new java.util.LinkedHashMap<>(payload(
                true, "", 1, List.of(unit("u1", "101", 1, 1))
        ));
        Map<String, Object> building = new java.util.LinkedHashMap<>(
                (Map<String, Object>) ((List<?>) response.get("buildings")).getFirst()
        );
        building.put("districtName", "용인시 기흥구");
        building.put("roadAddress", "공세로90번길 32");
        building.put("address", "용인시 기흥구 공세로90번길 32 공세동 289-2");
        response.put("buildings", List.of(building));
        GeminiApiTransport transport = request -> envelope(response, "STOP", 500, 100);

        GeminiPdfExtractionResult result = extractor(transport, 0).extract(List.of(
                new GeminiPdfDocument("doc-1", "GH", "매입임대", "목록.pdf", onePagePdf(), 1)
        ));

        assertThat(result.units().getFirst().address()).isEqualTo("용인시 기흥구 공세로90번길 32");
        assertThat(result.units().getFirst().roadAddress()).isEqualTo("용인시 기흥구 공세로90번길 32");
    }

    @Test
    void 신규분석은_후속호출_예약분을_침범하지_않는다() {
        GeminiDailyCallBudget budget = new GeminiDailyCallBudget(temporary, 3, 1);
        budget.acquire(false);
        budget.acquire(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> budget.acquire(false))
                .isInstanceOf(GeminiDailyQuotaExceededException.class)
                .hasMessageContaining("신규 분석");

        budget.acquire(true);
        assertThat(budget.usedToday()).isEqualTo(3);
    }

    @Test
    void PDF마다_독립적으로_한번씩_호출한다() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> {
            calls.incrementAndGet();
            return envelope(Map.of(
                    "documents", List.of(), "buildings", List.of(), "units", List.of(),
                    "complete", true, "nextCursor", "", "warnings", List.of()
            ), "STOP", 500, 50);
        };
        GeminiPdfHousingExtractor extractor = extractor(transport, 0);
        List<GeminiPdfDocument> documents = new java.util.ArrayList<>();
        for (int index = 1; index <= 5; index++) {
            documents.add(new GeminiPdfDocument(
                    "doc-" + index, "SH", "같은 공고", "문서" + index + ".pdf", onePagePdf()
            ));
        }

        GeminiPdfBatchExtractionResult result = extractor.extractAll(documents);

        assertThat(result.plannedBatchCount()).isEqualTo(5);
        assertThat(result.completedBatchCount()).isEqualTo(5);
        assertThat(result.apiCallCount()).isEqualTo(5);
        assertThat(result.complete()).isTrue();
        assertThat(calls).hasValue(5);
    }

    @Test
    void 일반문서도_구체적인_누락행이_있으면_한번만_보정한다() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> {
            calls.incrementAndGet();
            return envelope(payload(true, "", 4, List.of(
                    unit("u1", "101", 1, 1)
            )), "STOP", 500, 100);
        };

        GeminiPdfExtractionResult result = extractor(transport, 4, 20 * 1024 * 1024L, 50)
                .extract(List.of(new GeminiPdfDocument(
                        "doc-1", "SH", "소형 공고", "목록.pdf", onePagePdf(), 4)));

        assertThat(result.complete()).isFalse();
        assertThat(result.apiCallCount()).isEqualTo(2);
        assertThat(calls).hasValue(2);
    }

    @Test
    void 소형문서_부분캐시는_다음실행에_누락행을_한번_보정한다() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> {
            if (calls.getAndIncrement() == 0) {
                return envelope(payload(true, "", 2, List.of(unit("u1", "101", 1, 1))), "STOP", 500, 100);
            }
            assertThat(request).contains("missingExtractionIndexes", "[2]");
            return envelope(payload(true, "", 2, List.of(unit("u2", "102", 1, 2))), "STOP", 500, 100);
        };
        GeminiPdfHousingExtractor firstRun = extractor(transport, 0, 20 * 1024 * 1024L, 50);
        GeminiPdfHousingExtractor extractor = extractor(transport, 4, 20 * 1024 * 1024L, 50);
        var document = new GeminiPdfDocument("doc-1", "SH", "소형 공고", "목록.pdf", onePagePdf(), 2);

        assertThat(firstRun.extract(List.of(document)).complete()).isFalse();
        GeminiPdfExtractionResult repaired = extractor.extract(List.of(document));
        assertThat(repaired.complete()).isTrue();
        assertThat(repaired.units()).extracting(DetailedHousingUnit::unitNumber).containsExactly("101", "102");
        assertThat(calls).hasValue(2);
    }

    @Test
    void 대형문서_보정결과가_개선되지_않으면_즉시_중단한다() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        GeminiApiTransport transport = request -> {
            calls.incrementAndGet();
            return envelope(payload(true, "", 60, List.of(
                    unit("u1", "101", 1, 1)
            )), "STOP", 500, 100);
        };

        GeminiPdfExtractionResult result = extractor(transport, 4, 20 * 1024 * 1024L, 50)
                .extract(List.of(new GeminiPdfDocument(
                        "doc-1", "GH", "대형 공고", "목록.pdf", onePagePdf(), 60)));

        assertThat(result.complete()).isFalse();
        assertThat(result.apiCallCount()).isEqualTo(2);
        assertThat(calls).hasValue(2);
    }

    private GeminiPdfHousingExtractor extractor(GeminiApiTransport transport, int continuations) {
        return extractor(transport, continuations, 20 * 1024 * 1024L);
    }

    private GeminiPdfHousingExtractor extractor(
            GeminiApiTransport transport,
            int continuations,
            long maxFileBytes
    ) {
        return extractor(transport, continuations, maxFileBytes, 1);
    }

    private GeminiPdfHousingExtractor extractor(
            GeminiApiTransport transport,
            int continuations,
            long maxFileBytes,
            int continuationMinExpectedUnits
    ) {
        GeminiPdfParserProperties properties = new GeminiPdfParserProperties(
                "key", "https://generativelanguage.googleapis.com", "gemini-3-flash-preview",
                true, maxFileBytes,
                49_152, "low", Duration.ofSeconds(10), 20, 4, continuations,
                continuationMinExpectedUnits, temporary.resolve("cache").toString(), 1, 120
        );
        return new GeminiPdfHousingExtractor(
                properties, transport, new GeminiPdfExtractionCache(properties.cachePath()),
                new GeminiDailyCallBudget(properties.cachePath(), 20, 4)
        );
    }

    private byte[] onePagePdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] blankPagePdf(int pages) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int index = 0; index < pages; index++) document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }

    private int subsetSize(byte[] sourceBytes, List<Integer> pages) {
        try (PDDocument source = org.apache.pdfbox.Loader.loadPDF(sourceBytes);
             PDDocument target = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int page : pages) target.importPage(source.getPage(page - 1));
            target.save(output);
            return output.size();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private byte[] fivePageSupplyPdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int page = 1; page <= 5; page++) {
                PDPage pdfPage = new PDPage();
                document.addPage(pdfPage);
                if (page != 3) continue;
                try (PDPageContentStream content = new PDPageContentStream(document, pdfPage)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 700);
                    content.showText("1 row");
                    content.newLineAtOffset(0, -20);
                    content.showText("2 row");
                    content.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] twoPageRowPdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int page = 1; page <= 2; page++) {
                PDPage pdfPage = new PDPage();
                document.addPage(pdfPage);
                try (PDPageContentStream content = new PDPageContentStream(document, pdfPage)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 700);
                    content.showText(page + " row");
                    content.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private int pdfPageCount(byte[] bytes) {
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            return document.getNumberOfPages();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private Map<String, Object> payload(
            boolean complete,
            String cursor,
            int expected,
            List<Map<String, Object>> units
    ) {
        Map<String, Object> building = new java.util.LinkedHashMap<>();
        building.put("buildingId", "b1");
        building.put("documentId", "doc-1");
        building.put("complexName", "테스트주택");
        building.put("provinceName", "서울특별시");
        building.put("districtName", "강동구");
        building.put("roadAddress", "서울특별시 강동구 천호대로 1");
        building.put("housingType", "다세대");
        building.put("elevatorAvailable", true);
        building.put("additionalAttributes", List.of(Map.of(
                "name", "옥상출입", "value", "불가", "scope", "BUILDING", "page", 3,
                "evidence", "옥상 출입 불가"
        )));
        return Map.of(
                "documents", List.of(Map.of(
                        "documentId", "doc-1", "documentType", "SUPPLY_LIST",
                        "relevantPages", List.of(3, 4), "expectedUnitCount", expected,
                        "additionalAttributes", List.of(Map.of(
                                "name", "신청방식", "value", "온라인",
                                "scope", "DOCUMENT", "page", 1, "evidence", "온라인 신청"
                        ))
                )),
                "buildings", List.of(building),
                "units", units,
                "complete", complete,
                "nextCursor", cursor,
                "warnings", List.of()
        );
    }

    private Map<String, Object> flatPayload(
            boolean complete,
            String cursor,
            int expected,
            List<Map<String, Object>> sourceUnits
    ) {
        Map<String, Object> building = new java.util.LinkedHashMap<>();
        building.put("buildingId", "b1");
        building.put("documentId", "doc-1");
        building.put("complexName", "테스트주택");
        building.put("provinceName", "서울특별시");
        building.put("districtName", "강동구");
        building.put("roadAddress", "서울특별시 강동구 천호대로 1");
        building.put("housingType", "다세대");
        building.put("elevatorAvailable", true);

        List<Map<String, Object>> flattenedUnits = sourceUnits.stream().map(source -> {
            Map<String, Object> copy = new java.util.LinkedHashMap<>(source);
            copy.remove("additionalAttributes");
            copy.remove("rentalConditions");
            copy.put("commonArea", 6.885);
            copy.put("rentalArea", 51.305);
            return copy;
        }).toList();
        return Map.of(
                "documents", List.of(Map.of(
                        "documentId", "doc-1", "documentType", "SUPPLY_LIST",
                        "relevantPages", List.of(3, 4), "expectedUnitCount", expected
                )),
                "buildings", List.of(building),
                "units", flattenedUnits,
                "rentalConditions", List.of(Map.of(
                        "unitId", "u1", "conditionCode", "BASE", "conditionName", "기본",
                        "rentDeposit", 10_000_000, "monthlyRent", 250_000,
                        "page", 3, "evidence", "보증금 10,000,000 월세 250,000"
                )),
                "additionalAttributes", List.of(
                        Map.of("name", "신청방식", "value", "온라인", "scope", "DOCUMENT",
                                "documentId", "doc-1", "buildingId", "", "unitId", "",
                                "page", 1, "evidence", "온라인 신청"),
                        Map.of("name", "옥상출입", "value", "불가", "scope", "BUILDING",
                                "documentId", "", "buildingId", "b1", "unitId", "",
                                "page", 3, "evidence", "옥상 출입 불가"),
                        Map.of("name", "베란다", "value", "있음", "scope", "UNIT",
                                "documentId", "", "buildingId", "", "unitId", "u1",
                                "page", 3, "evidence", "베란다 있음")
                ),
                "complete", complete,
                "nextCursor", cursor,
                "warnings", List.of()
        );
    }

    private Map<String, Object> unit(String id, String number, int page, int extractionIndex) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("unitId", id);
        result.put("buildingId", "b1");
        result.put("sourceTableId", "doc-1/table-1");
        result.put("extractionIndex", extractionIndex);
        result.put("sourceRowLabel", Integer.toString(extractionIndex));
        result.put("unitNumber", number);
        result.put("floor", Integer.parseInt(number.substring(0, 1)));
        result.put("exclusiveArea", 39.5);
        result.put("roomCount", 2);
        result.put("rentDeposit", 10_000_000);
        result.put("monthlyRent", 250_000);
        result.put("supplyCount", 1);
        result.put("verifiedNullFields", List.of());
        result.put("page", page);
        result.put("evidence", number + "호 전용 39.5㎡");
        result.put("additionalAttributes", List.of(Map.of(
                "name", "베란다", "value", "있음", "scope", "UNIT", "page", page,
                "evidence", "베란다 있음"
        )));
        result.put("rentalConditions", List.of());
        return result;
    }

    private Map<String, Object> condition(
            String unitId, String code, String name, long deposit, long rent
    ) {
        return Map.of(
                "unitId", unitId,
                "conditionCode", code,
                "conditionName", name,
                "rentDeposit", deposit,
                "monthlyRent", rent,
                "page", 1,
                "evidence", name + " 보증금 " + deposit + " 월임대료 " + rent
        );
    }

    private String sha256(byte[] value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private List<Map<String, Object>> units(int fromInclusive, int toInclusive) {
        return IntStream.rangeClosed(fromInclusive, toInclusive)
                .mapToObj(index -> unit("u" + index, Integer.toString(100 + index),
                        1 + (index - 1) / 25, index))
                .toList();
    }

    private Map<String, Object> unitWithElevator(
            String id, String number, int page, int extractionIndex, boolean elevatorAvailable
    ) {
        Map<String, Object> result = unit(id, number, page, extractionIndex);
        result.put("elevatorAvailable", elevatorAvailable);
        return result;
    }

    private String envelope(Map<String, Object> payload, String finishReason, int prompt, int output) {
        return JSON.writeValueAsString(Map.of(
                "candidates", List.of(Map.of(
                        "finishReason", finishReason,
                        "content", Map.of("parts", List.of(Map.of("text", JSON.writeValueAsString(payload))))
                )),
                "usageMetadata", Map.of(
                        "promptTokenCount", prompt, "candidatesTokenCount", output,
                        "thoughtsTokenCount", 0, "totalTokenCount", prompt + output
                )
        ));
    }
}
