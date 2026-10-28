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
