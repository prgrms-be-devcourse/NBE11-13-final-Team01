package com.zipick.external.housingdetail;

import com.zipick.external.sh.ShPdfHousingParser;
import com.zipick.notice.dto.NoticeResponse;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LhHousingDetailSourceTest {

    @Test
    void 동의서만_있는_서식은_주택분석에서_제외하고_공고문이_포함되면_유지한다() {
        assertThat(LhHousingDetailSource.isConsentFormOnly("붙임1.개인정보 수집 이용 동의서.pdf")).isTrue();
        assertThat(LhHousingDetailSource.isConsentFormOnly("2-2.샘플_금융정보제공동의서.pdf")).isTrue();
        assertThat(LhHousingDetailSource.isConsentFormOnly("모집공고문 및 동의서.pdf")).isFalse();
        assertThat(LhHousingDetailSource.isConsentFormOnly("공급주택목록.pdf")).isFalse();
    }

    @Test
    void 주택행이_없어도_공고문_추가정보는_첨부이력에_보존한다() {
        var source = source(new StubDocumentClient("", new byte[0]));
        var attachment = new com.zipick.notice.dto.NoticeAttachmentResponse("LH_FILE:1", "NOTICE", "공고.pdf", "https://example.com/1", null);
        var attributes = java.util.Map.<String, List<java.util.Map<String, Object>>>of("LH_FILE:1", List.of(
                java.util.Map.of("scope", "DOCUMENT", "name", "지원금액", "value", "2억원", "page", 1, "evidence", "2억원")));
        assertThat(source.preserveDocumentAttributes(List.of(attachment), attributes)).singleElement().satisfies(result -> {
            assertThat(result.rawPayload()).contains("지원금액", "2억원", "DOCUMENT");
            assertThat(result.sourceKey()).isEqualTo("LH_FILE:1");
        });
    }

    @Test
    void 같은_단지와_주택형의_팸플릿은_기존_공급행에_정보를_합친다() {
        var source = source(new StubDocumentClient("", new byte[0]));
        var primary = new DetailedHousingUnit("GEMINI:main/item-1", "구리갈매역세권 A-1BL 신혼희망타운 행복주택", null, null,
                "경기도 구리시 갈매동 1", null, null, null, new java.math.BigDecimal("55.94"), null,
                "55A-1", null, 175200000L, 730000L, "{\"primaryData\":true}");
        var pamphlet = new DetailedHousingUnit("GEMINI:pamphlet/item-1", "구리갈매역세권 A-1블록 신혼희망타운", null, null,
                "구리갈매역세권 A-1블록", null, null, null, new java.math.BigDecimal("55.94"), null,
                "55A-1", null, null, null, "{\"extraData\":true}");
        var result = new java.util.ArrayList<>(List.of(primary));
        source.mergePamphletUnits(result, List.of(pamphlet));
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().rentDeposit()).isEqualTo(175200000L);
        assertThat(result.getFirst().rawPayload()).contains("primaryData", "extraData");
    }

    @Test
    void 주택행이_없는_팸플릿의_공통정보는_기존_행의_문서정보로_보존한다() {
        var source = source(new StubDocumentClient("", new byte[0]));
        var primary = new DetailedHousingUnit("GEMINI:main/item-1", "단지", null, null,
                "경기도 구리시 갈매동 1", null, null, null, new java.math.BigDecimal("55.94"), null,
                "55A-1", null, 175200000L, 730000L, "{\"parser\":\"DIRECT_HOUSING_V19_BOUNDED_REPAIR\"}");
        var result = new java.util.ArrayList<>(List.of(primary));
        source.preservePamphletAttributes(result, List.of(java.util.Map.of("scope", "DOCUMENT", "name", "주민공동시설 종류", "value", "스마트멀티룸", "page", 12, "evidence", "스마트멀티룸")));
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().rawPayload()).contains("스마트멀티룸", "DOCUMENT", "DIRECT_HOUSING_V19_BOUNDED_REPAIR");
        assertThat(result.getFirst().rentDeposit()).isEqualTo(175200000L);
    }

    @Test
    void LH_AI_성공한_첨부만_이력을_남기고_다음_실행에서_건너뛴다() throws Exception {
        var source = source(new StubDocumentClient(
                "<a href=\"javascript:fileDownLoad('77');\">모집공고문.pdf</a>", emptyPdf()));
        var history = org.mockito.Mockito.mock(com.zipick.notice.repository.DocumentParseHistoryRepository.class);
        var ai = org.mockito.Mockito.mock(com.zipick.external.ai.GeminiPdfHousingDetailSupport.class);
        source.setHistoryRepository(history); source.setGeminiSupport(ai);
        org.mockito.Mockito.when(ai.enabled()).thenReturn(true);
        org.mockito.Mockito.when(ai.parserVersion()).thenReturn("AI_TEST");
        var response = new com.zipick.external.ai.GeminiPdfHousingDetailSupport.SupplementResult(
                List.of(unit("LH_PDF:1", "서울특별시 강남구 테헤란로 1")), true, true);
        org.mockito.Mockito.when(ai.supplementWithStatus(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(response);
        var first = source.extract(notice("https://apply.lh.or.kr/detail"));
        assertThat(first.parseCheckpoints()).hasSize(1);
        var checkpoint = first.parseCheckpoints().getFirst();
        assertThat(checkpoint.parserVersion()).isEqualTo("LH_AI_TEST");
        var success = new com.zipick.notice.domain.DocumentParseHistory(checkpoint.documentKey(), checkpoint.parserVersion());
        success.markSuccess(checkpoint.size(), checkpoint.unitCount());
        org.mockito.Mockito.when(history.findByDocumentKeyAndParserVersion(checkpoint.documentKey(), checkpoint.parserVersion()))
                .thenReturn(java.util.Optional.of(success));
        var second = source.extract(notice("https://apply.lh.or.kr/detail"));
        assertThat(second.preservesExistingHousings()).isTrue();
        assertThat(second.housings()).isEmpty();
        org.mockito.Mockito.verify(ai, org.mockito.Mockito.times(1)).supplementWithStatus(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void LH_AI_실패하고_기본결과를_사용하면_AI_성공이력을_남기지_않는다() throws Exception {
        var source = source(new StubDocumentClient(
                "<a href=\"javascript:fileDownLoad('77');\">모집공고문.pdf</a>", emptyPdf()));
        var history = org.mockito.Mockito.mock(com.zipick.notice.repository.DocumentParseHistoryRepository.class);
        var ai = org.mockito.Mockito.mock(com.zipick.external.ai.GeminiPdfHousingDetailSupport.class);
        source.setHistoryRepository(history); source.setGeminiSupport(ai);
        org.mockito.Mockito.when(ai.enabled()).thenReturn(true);
        org.mockito.Mockito.when(ai.parserVersion()).thenReturn("AI_TEST");
        var response = new com.zipick.external.ai.GeminiPdfHousingDetailSupport.SupplementResult(
                List.of(unit("LH_PDF:1", "서울특별시 강남구 테헤란로 1")), false, false);
        org.mockito.Mockito.when(ai.supplementWithStatus(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(response);
        var result = source.extract(notice("https://apply.lh.or.kr/detail"));
        assertThat(result.housings()).hasSize(1);
        assertThat(result.parseCheckpoints()).isEmpty();
    }

    private byte[] emptyPdf() throws Exception {
        try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument();
             var output = new java.io.ByteArrayOutputStream()) {
            pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            pdf.save(output);
            return output.toByteArray();
        }
    }

    @Test
    void unchangedAttachmentIsSkippedAndChangedBytesAreParsed() throws Exception {
        byte[] workbook = LhSpreadsheetTestFixture.oneHousingWorkbook();
        var client = new StubDocumentClient("<a href=\"javascript:fileDownLoad('77');\">공급주택목록.xlsx</a>", workbook);
        var source = source(client);
        var history = org.mockito.Mockito.mock(com.zipick.notice.repository.DocumentParseHistoryRepository.class);
        source.setHistoryRepository(history);
        var first = source.extract(notice("https://apply.lh.or.kr/detail"));
        var checkpoint = first.parseCheckpoints().getFirst();
        var success = new com.zipick.notice.domain.DocumentParseHistory(checkpoint.documentKey(), checkpoint.parserVersion());
        success.markSuccess(checkpoint.size(), checkpoint.unitCount());
        org.mockito.Mockito.when(history.findByDocumentKeyAndParserVersion(checkpoint.documentKey(), checkpoint.parserVersion()))
                .thenReturn(java.util.Optional.of(success));
        var second = source.extract(notice("https://apply.lh.or.kr/detail"));
        assertThat(second.preservesExistingHousings()).isTrue();
        assertThat(second.housings()).isEmpty();
        byte[] changed = java.util.Arrays.copyOf(workbook, workbook.length + 1);
        var changedSource = source(new StubDocumentClient(
                "<a href=\"javascript:fileDownLoad('77');\">공급주택목록.xlsx</a>", changed));
        changedSource.setHistoryRepository(history);
        var changedResult = changedSource.extract(notice("https://apply.lh.or.kr/detail"));
        assertThat(changedResult.preservesExistingHousings()).isFalse();
        assertThat(changedResult.housings()).hasSize(1);
    }

    @Test
    void 상세페이지에_내장된_JSON을_먼저_읽는다() {
        String html = """
                <script>
                const list = JSON.parse('[{"adr":"강원특별자치도 춘천시 효자문길 38 (효자동)","hoAdmNo":"A1","hoNo":"202","allCnt":2,"ddoAr":"59.4","rmCnt":"2","vldVlNm":"2층"},{"adr":"강원특별자치도 춘천시 효자문길 38 (효자동)","hoAdmNo":"A2","hoNo":"302","allCnt":2,"ddoAr":"59.4","rmCnt":"2","vldVlNm":"3층"}]');
                </script>
                """;
        ExternalDocumentClient client = new StubDocumentClient(html, null);
        LhHousingDetailSource source = source(client);

        List<DetailedHousingUnit> units = source.extract(notice("https://apply.lh.or.kr/detail")).housings();

        assertThat(units).hasSize(2);
        assertThat(units).extracting(DetailedHousingUnit::unitNumber).containsExactly("202", "302");
    }

    @Test
    void JSON이_없으면_공급주택목록_엑셀을_사용한다() throws Exception {
        byte[] workbook = LhSpreadsheetTestFixture.oneHousingWorkbook();
        String html = "<a href=\"javascript:fileDownLoad('77');\">공급주택목록.xlsx</a>";
        ExternalDocumentClient client = new StubDocumentClient(html, workbook);
        LhHousingDetailSource source = source(client);

        List<DetailedHousingUnit> units = source.extract(notice("https://apply.lh.or.kr/detail")).housings();

        assertThat(units).hasSize(1);
        assertThat(units.getFirst().address()).isEqualTo("강원특별자치도 춘천시 효자문길 38 (효자동)");
    }

    @Test
    void 실제_LH_href_첨부링크를_읽는다() throws Exception {
        byte[] workbook = LhSpreadsheetTestFixture.oneHousingWorkbook();
        String html = "<a class=\"file\" href=\"javascript:fileDownLoad('68932266');\">공급주택목록.xlsx</a>";
        LhHousingDetailSource source = source(new StubDocumentClient(html, workbook));

        List<DetailedHousingUnit> units = source.extract(notice("https://apply.lh.or.kr/detail")).housings();

        assertThat(units).hasSize(1);
    }

    @Test
    void 주택파싱결과가_없어도_공고문첨부를_저장한다() {
        String html = "<a href=\"javascript:fileDownLoad('68932266');\">영구임대 모집공고문.pdf</a>";
        LhHousingDetailSource source = source(new StubDocumentClient(html, new byte[0]));

        HousingDetailResult result = source.extract(notice("https://apply.lh.or.kr/detail"));

        assertThat(result.attachments()).hasSize(1);
        assertThat(result.attachments().getFirst().attachmentType()).isEqualTo("ANNOUNCEMENT");
        assertThat(result.attachments().getFirst().sourceUrl())
                .isEqualTo("https://apply.lh.or.kr/lhapply/lhFile.do?fileid=68932266");
    }

    @Test
    void JSON에_없는_금액만_엑셀에서_보완한다() throws Exception {
        byte[] workbook = LhSpreadsheetTestFixture.oneHousingWorkbook();
        String html = """
                <script>
                const list = JSON.parse('[{"adr":"강원특별자치도 춘천시 효자문길 38 (효자동)","hoAdmNo":"A1","hoNo":"202","allCnt":1,"ddoAr":"59.4","rmCnt":"2","vldVlNm":"2층"}]');
                </script>
                <a href="javascript:fileDownLoad('77');">공급주택목록.xlsx</a>
                """;
        LhHousingDetailSource source = source(new StubDocumentClient(html, workbook));

        List<DetailedHousingUnit> units = source.extract(notice("https://apply.lh.or.kr/detail")).housings();

        assertThat(units).hasSize(1);
        assertThat(units.getFirst().exclusiveArea()).isEqualByComparingTo("59.4");
        assertThat(units.getFirst().rentDeposit()).isEqualTo(6_913_000L);
        assertThat(units.getFirst().monthlyRent()).isEqualTo(135_150L);
    }

    @Test
    void JSON이_있으면_일반_공고문에서_찾은_다른_주소를_신규_주택으로_추가하지_않는다() {
        LhHousingDetailSource source = source(new StubDocumentClient("", null));
        DetailedHousingUnit json = unit("LH_DETAIL:A1", "전북특별자치도 김제시 하동1길 127");
        DetailedHousingUnit noticePdf = unit("LH_PDF:77:1", "전북특별자치도 김제시 하동 1");

        List<DetailedHousingUnit> merged = source.mergeAttachmentDetails(List.of(json), List.of(noticePdf));

        assertThat(merged).containsExactly(json);
    }

    @Test
    void JSON이_있어도_구조화된_주택목록의_추가_주택은_허용한다() {
        LhHousingDetailSource source = source(new StubDocumentClient("", null));
        DetailedHousingUnit json = unit("LH_DETAIL:A1", "강원특별자치도 춘천시 효자문길 38");
        DetailedHousingUnit spreadsheet = unit("LH_FILE:77:0:2", "강원특별자치도 춘천시 효자문길 40");

        List<DetailedHousingUnit> merged = source.mergeAttachmentDetails(List.of(json), List.of(spreadsheet));

        assertThat(merged).containsExactly(json, spreadsheet);
    }

    @Test
    void 동일주소의_AI_공급행은_서로_합치지_않는다() {
        var source = source(new StubDocumentClient("", null));
        var first = new DetailedHousingUnit("GEMINI:abc:LH_FILE:1/item-1", null, null, null,
                "전남광주통합특별시 여수시 미평로 77", "101,102", null, null,
                new java.math.BigDecimal("26.37"), null, "26", null, 2520000L, 50160L, null);
        var second = new DetailedHousingUnit("GEMINI:abc:LH_FILE:1/item-2", null, null, null,
                "전남광주통합특별시 여수시 미평로 77", "103", null, null,
                new java.math.BigDecimal("31.32"), null, "31", null, 2993000L, 59570L, null);
        assertThat(source.mergeAttachmentDetails(List.of(), List.of(first, second))).containsExactly(first, second);
        var embedded = unit("LH_DETAIL:1", "전남광주통합특별시 여수시 미평로 77");
        assertThat(source.mergeAttachmentDetails(List.of(embedded), List.of(first, second)))
                .hasSize(2).extracting(DetailedHousingUnit::exclusiveArea)
                .containsExactly(new java.math.BigDecimal("26.37"), new java.math.BigDecimal("31.32"));
    }

    @Test
    void 기존_행과_AI_행_결합시_조건별_임대료를_보존한다() {
        var source = source(new StubDocumentClient("", null));
        var embedded = unit("LH_DETAIL:1", "서울특별시 강남구 테헤란로 1");
        var condition = new DetailedRentalCondition("NA", "나군", 7706000L, 106460L, "{}");
        var ai = new DetailedHousingUnit("GEMINI:abc:LH_FILE:1/item-1", null, null, null,
                "서울특별시 강남구 테헤란로 1", null, null, null,
                new java.math.BigDecimal("31.32"), null, "31", null, 3328000L, 66210L,
                "{\"parser\":\"DIRECT_HOUSING_V19_BOUNDED_REPAIR\"}", null, null, 1, null, null,
                null, null, null, null, null, null, 30L, List.of(condition));
        var result = source.mergeAttachmentDetails(List.of(embedded), List.of(ai));
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().rentalConditions()).containsExactly(condition);
        assertThat(result.getFirst().supplyCount()).isEqualTo(30L);
    }

    @Test
    void 주소_부가설명이_달라도_도로명과_호수가_같으면_병합한다() {
        var source = source(new StubDocumentClient("", null));
        var json = new DetailedHousingUnit("LH_DETAIL:1", null, null, null,
                "경기도 부천시 소사구 소사로148번길 58-9(소사본동) 한결주택 101동/소사구",
                "101동", "403호", null, null, null, null, null, null, null, null);
        var xlsx = new DetailedHousingUnit("LH_FILE:1", null, null, null,
                "경기도 부천시 소사구 소사로148번길 58-9(소사본동) 한결주택",
                "101", "403", null, new java.math.BigDecimal("54.26"), null, null, null, null, null, null);
        var result = source.mergeAttachmentDetails(List.of(json), List.of(xlsx));
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().exclusiveArea()).isEqualByComparingTo("54.26");
    }

    @Test
    void 같은_도로명과_호수라도_동이_다르면_병합하지_않는다() {
        var source = source(new StubDocumentClient("", null));
        var a = new DetailedHousingUnit("LH_DETAIL:1", null, null, null, "서울특별시 강남구 테헤란로 1 건물A",
                "101동", "403호", null, null, null, null, null, null, null, null);
        var b = new DetailedHousingUnit("LH_FILE:1", null, null, null, "서울특별시 강남구 테헤란로 1 건물B",
                "102동", "403호", null, null, null, null, null, null, null, null);
        assertThat(source.mergeAttachmentDetails(List.of(a), List.of(b))).hasSize(2);
    }

    @Test
    void 같은_이름의_문서는_엑셀_PDF_HWP_순으로_하나만_선택한다() {
        LhHousingDetailSource source = source(new StubDocumentClient("", null));

        List<List<LhAttachment>> groups = source.groupByDocumentName(List.of(
                new LhAttachment("1", "주택목록.pdf"),
                new LhAttachment("2", "주택목록.hwp"),
                new LhAttachment("3", "주택목록.xlsx"),
                new LhAttachment("4", "주택목록(상세).pdf")
        ));

        assertThat(groups).hasSize(2);
        assertThat(groups.stream()
                .filter(group -> group.size() == 3)
                .findFirst()
                .orElseThrow()
                .stream()
                .map(LhAttachment::fileName))
                .containsExactly("주택목록.xlsx", "주택목록.pdf", "주택목록.hwp");
    }

    @Test
    void 공고문도_주택정보_추출_후보에_포함한다() {
        LhHousingDetailSource source = source(new StubDocumentClient("", null));

        List<List<LhAttachment>> groups = source.groupByDocumentName(List.of(
                new LhAttachment("1", "군산시 국민임대 입주자 모집 공고문.hwp"),
                new LhAttachment("2", "군산시 국민임대 입주자 모집 공고문.pdf")
        ));

        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst()).extracting(LhAttachment::fileName)
                .containsExactly(
                        "군산시 국민임대 입주자 모집 공고문.pdf",
                        "군산시 국민임대 입주자 모집 공고문.hwp"
                );
    }

    @Test
    void 첨부파일과_JSON의_평면도_주소를_수집한다() {
        String html = """
                <script>
                const plans = JSON.parse('[{"floorPlanUrl":"/images/59a.png","name":"59A 평면도"}]');
                </script>
                <a href="javascript:fileDownLoad('88');">단지 도면 및 평면도.pdf</a>
                """;
        LhHousingDetailSource source = source(new StubDocumentClient(html, new byte[0]));

        HousingDetailResult result = source.extract(notice("https://apply.lh.or.kr/detail"));

        assertThat(result.attachments()).hasSize(2);
        assertThat(result.attachments()).extracting(attachment -> attachment.sourceUrl())
                .contains(
                        "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=88",
                        "https://apply.lh.or.kr/images/59a.png"
                );
    }

    @Test
    void JSON의_평면도와_투시도를_해당_주택에_연결한다() {
        String html = """
                <script>
                const houses = JSON.parse('[{"adr":"대구광역시 수성구 연호동 100","hoAdmNo":"A1","sbdLgoNo":"C02624"}]');
                const images = JSON.parse('[[{"sbdLgoNo":"C02624","cmnAhflSn":44673097,"cmnAhflNm":"55A평면도.PNG","lsSplInfUplFlDsCdNm":"평면도","persCmnAhflSn":44673100,"persCmnAhflNm":"단지투시도.jpg"}]]');
                </script>
                """;
        LhHousingDetailSource source = source(new StubDocumentClient(html, null));

        HousingDetailResult result = source.extract(notice("https://apply.lh.or.kr/detail"));

        assertThat(result.mediaByUnitKey()).containsKey("LH_DETAIL:A1");
        assertThat(result.mediaByUnitKey().get("LH_DETAIL:A1"))
                .extracting(DetailedHousingMedia::mediaType)
                .containsExactlyInAnyOrder("FLOOR_PLAN", "BUILDING_PHOTO");
    }

    @Test
    void 실제_LH_강원_공고에서_83개_호와_59개_주소를_읽는다() {
        String detailUrl = System.getenv("LH_DETAIL_SAMPLE_URL");
        assumeTrue(detailUrl != null && !detailUrl.isBlank());
        LhHousingDetailSource source = source(new ExternalDocumentClient());

        List<DetailedHousingUnit> units = source.extract(notice(detailUrl)).housings();

        assertThat(units).hasSize(83);
        assertThat(units.stream().map(DetailedHousingUnit::address).distinct()).hasSize(59);
    }

    private LhHousingDetailSource source(ExternalDocumentClient client) {
        LhPdfHousingParser pdfParser = new LhPdfHousingParser();
        return new LhHousingDetailSource(
                client,
                new LhSpreadsheetHousingParser(),
                pdfParser,
                new LhHwpHousingParser(pdfParser),
                new ShPdfHousingParser()
        );
    }

    private NoticeResponse notice(String url) {
        return new NoticeResponse(
                "MYHOME", "21394", "공고중", "강원 매입임대", "LH", null,
                "2026-10-07", null, null, null, null, url, null, null, List.of(), List.of()
        );
    }

    private DetailedHousingUnit unit(String sourceKey, String address) {
        return new DetailedHousingUnit(
                sourceKey, null, null, null, address,
                null, null, null, null, null, null, null, null, null, null
        );
    }

    private static class StubDocumentClient extends ExternalDocumentClient {
        private final String html;
        private final byte[] attachment;

        private StubDocumentClient(String html, byte[] attachment) {
            this.html = html;
            this.attachment = attachment;
        }

        @Override
        public String getText(URI uri) {
            return html;
        }

        @Override
        public byte[] getBytes(URI uri) {
            return attachment;
        }
    }
}
