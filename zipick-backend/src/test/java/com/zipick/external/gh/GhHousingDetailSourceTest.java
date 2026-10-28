package com.zipick.external.gh;

import com.zipick.external.housingdetail.ExternalDocumentClient;
import com.zipick.notice.dto.NoticeResponse;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GhHousingDetailSourceTest {

    @Test
    void supportsGhSourceAndOfficialHostOnly() {
        GhHousingDetailSource source = new GhHousingDetailSource(null);
        assertThat(source.supports(notice("GH", null))).isTrue();
        assertThat(source.supports(notice("MYHOME", "https://apply.gh.or.kr/detail"))).isTrue();
        assertThat(source.supports(notice("MYHOME", null))).isFalse();
        assertThat(source.supports(notice("MYHOME", "https://example.com/detail"))).isFalse();
    }

    @Test
    void parsesComplexSupplyAttachmentAndMediaFromDetailHtml() {
        String html = """
                <table><tr><th>공고문</th><td>
                  <a href="https://apply-cdn.gh.or.kr/sr/sr7150/selectFileDown.do?pbancNo=839&amp;atchFileSn=1">
                    모집 공고.pdf (12345 Byte)</a>
                </td></tr></table>
                <div id="tab1">
                  <table><caption>사업지구별 공급정보</caption><tbody>
                    <tr><th>지구명</th><td>판교2밸리</td><th>소재지</th><td>경기도 성남시 수정구 금토동 1</td></tr>
                    <tr><th>난방방식</th><td>지역난방</td><th>입주예정월</th><td>2027.01</td></tr>
                  </tbody></table>
                  <button data-show="img1tab1">평면도</button>
                  <div id="img1tab1"><img src="/images/floor.png" alt="36형 평면도"></div>
                  <table><tbody class="tb_house">
                    <tr class="tb_house_con"><td><button data-houseTyCd="36A">36A 청년</button></td>
                    <td>36.12</td><td>5</td><td>36,110,000</td><td>372,100</td><td>공고중</td></tr>
                  </tbody></table>
                </div>
                """;
        ExternalDocumentClient client = new ExternalDocumentClient() {
            @Override public String getText(URI uri) { return html; }
        };
        GhHousingDetailSource source = new GhHousingDetailSource(client);
        var history = org.mockito.Mockito.mock(com.zipick.notice.repository.DocumentParseHistoryRepository.class);
        source.setHistoryRepository(history);
        NoticeResponse notice = new NoticeResponse(
                "GH", "839", "공고중", "공고", "경기주택도시공사", null,
                "2026-10-16", null, "2026-10-26", "2026-10-28", null,
                "https://apply.gh.or.kr/detail", null, null, List.of(), List.of()
        );

        var result = source.extract(notice);

        assertThat(result.housings()).hasSize(1);
        var checkpoint = result.parseCheckpoints().getFirst();
        var success = new com.zipick.notice.domain.DocumentParseHistory(checkpoint.documentKey(), checkpoint.parserVersion());
        success.markSuccess(checkpoint.size(), checkpoint.unitCount());
        org.mockito.Mockito.when(history.findByDocumentKeyAndParserVersion(checkpoint.documentKey(), checkpoint.parserVersion()))
                .thenReturn(java.util.Optional.of(success));
        assertThat(source.extract(notice).preservesExistingHousings()).isTrue();
        assertThat(result.housings().getFirst().complexName()).isEqualTo("판교2밸리");
        assertThat(result.housings().getFirst().rentDeposit()).isEqualTo(36_110_000L);
        assertThat(result.housings().getFirst().supplyCount()).isEqualTo(5L);
        assertThat(result.attachments()).hasSize(1);
        assertThat(result.mediaByUnitKey().values().stream().flatMap(List::stream))
                .extracting(media -> media.mediaType()).containsExactly("FLOOR_PLAN");
        assertThat(result.mediaByUnitKey().values().stream().flatMap(List::stream).findFirst().orElseThrow()
                .rawPayload()).isEqualTo("{\"alt\":\"36형 평면도\"}");
    }

    @Test
    void skipsIncompleteSectionsAndClassifiesAttachmentsAndMedia() {
        String html = """
                <a href="/selectFileDown?id=1">공고문.pdf</a>
                <a href="/selectFileDown?id=2">주택목록.xlsx</a>
                <a href="/selectFileDown?id=3">도면.pdf</a>
                <a href="/selectFileDown?id=4"></a>
                <div id="tab-empty"><p>공급정보 표 없음</p></div>
                <div id="tab-no-address"><table><caption>사업지구별 공급정보</caption>
                  <tr><th>지구명</th><td>주소없음</td></tr></table></div>
                <div id="tab2"><table><caption>사업지구별 공급정보</caption>
                  <tr><th>단지명</th><td>테스트</td><th>주소</th><td>경기도 수원시 영통구 1</td></tr></table>
                  <button data-show="site">배치도</button><div id="site"><img src="/site.png"></div>
                  <button data-show="missing">위치도</button>
                  <button data-show="photo">사진</button><div id="photo"><img src="/photo.jpg"></div>
                  <table><tbody class="tb_house">
                    <tr class="tb_house_con"><td>59A</td><td>확인</td><td>-</td><td>확인</td><td>-</td></tr>
                    <tr class="tb_house_con"><td>짧음</td></tr>
                  </tbody></table>
                </div>
                """;
        ExternalDocumentClient client = new ExternalDocumentClient() {
            @Override public String getText(URI uri) { return html; }
        };
        GhHousingDetailSource source = new GhHousingDetailSource(client);

        var result = source.extract(notice("GH", "https://apply.gh.or.kr/detail"));

        assertThat(result.housings()).hasSize(1);
        assertThat(result.housings().getFirst().districtName()).isEqualTo("수원시 영통구");
        assertThat(result.housings().getFirst().rentDeposit()).isNull();
        assertThat(result.attachments()).extracting(item -> item.attachmentType())
                .containsExactly("ANNOUNCEMENT", "HOUSING_LIST", "FLOOR_PLAN", "OTHER");
        assertThat(result.mediaByUnitKey().values().stream().flatMap(List::stream))
                .extracting(item -> item.mediaType()).containsExactly("SITE_PLAN", "HOUSING_IMAGE");
    }

    private NoticeResponse notice(String source, String url) {
        return new NoticeResponse(source, "839", "공고중", "공고", "GH", null,
                null, null, null, null, null, url, null, null, List.of(), List.of());
    }
}
