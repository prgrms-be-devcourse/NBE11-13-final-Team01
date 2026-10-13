package com.zipick.external.housingdetail;

import com.zipick.notice.dto.NoticeResponse;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LhHousingDetailSourceTest {

    @Test
    void 상세페이지에_내장된_JSON을_먼저_읽는다() {
        String html = """
                <script>
                const list = JSON.parse('[{"adr":"강원특별자치도 춘천시 효자문길 38 (효자동)","hoAdmNo":"A1","hoNo":"202","allCnt":2,"ddoAr":"59.4","rmCnt":"2","vldVlNm":"2층"},{"adr":"강원특별자치도 춘천시 효자문길 38 (효자동)","hoAdmNo":"A2","hoNo":"302","allCnt":2,"ddoAr":"59.4","rmCnt":"2","vldVlNm":"3층"}]');
                </script>
                """;
        ExternalDocumentClient client = new StubDocumentClient(html, null);
        LhHousingDetailSource source = source(client);

        List<DetailedHousingUnit> units = source.extract(notice("https://apply.lh.or.kr/detail"));

        assertThat(units).hasSize(2);
        assertThat(units).extracting(DetailedHousingUnit::unitNumber).containsExactly("202", "302");
    }

    @Test
    void JSON이_없으면_공급주택목록_엑셀을_사용한다() throws Exception {
        byte[] workbook = LhSpreadsheetTestFixture.oneHousingWorkbook();
        String html = "<a href=\"javascript:fileDownLoad('77');\">공급주택목록.xlsx</a>";
        ExternalDocumentClient client = new StubDocumentClient(html, workbook);
        LhHousingDetailSource source = source(client);

        List<DetailedHousingUnit> units = source.extract(notice("https://apply.lh.or.kr/detail"));

        assertThat(units).hasSize(1);
        assertThat(units.getFirst().address()).isEqualTo("강원특별자치도 춘천시 효자문길 38 (효자동)");
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

        List<DetailedHousingUnit> units = source.extract(notice("https://apply.lh.or.kr/detail"));

        assertThat(units).hasSize(1);
        assertThat(units.getFirst().exclusiveArea()).isEqualByComparingTo("59.4");
        assertThat(units.getFirst().rentDeposit()).isEqualTo(6_913_000L);
        assertThat(units.getFirst().monthlyRent()).isEqualTo(135_150L);
    }

    @Test
    void 실제_LH_강원_공고에서_83개_호와_59개_주소를_읽는다() {
        String detailUrl = System.getenv("LH_DETAIL_SAMPLE_URL");
        assumeTrue(detailUrl != null && !detailUrl.isBlank());
        LhHousingDetailSource source = source(new ExternalDocumentClient());

        List<DetailedHousingUnit> units = source.extract(notice(detailUrl));

        assertThat(units).hasSize(83);
        assertThat(units.stream().map(DetailedHousingUnit::address).distinct()).hasSize(59);
    }

    private LhHousingDetailSource source(ExternalDocumentClient client) {
        return new LhHousingDetailSource(client, new LhSpreadsheetHousingParser(), new LhPdfHousingParser());
    }

    private NoticeResponse notice(String url) {
        return new NoticeResponse(
                "MYHOME", "21394", "공고중", "강원 매입임대", "LH", null,
                "2026-10-07", null, null, null, null, url, null, null, List.of()
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
