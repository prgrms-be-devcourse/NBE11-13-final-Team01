package com.zipick.external.sh;

import com.zipick.external.housingdetail.ExternalDocumentClient;
import com.zipick.external.housingdetail.HousingDetailEnricher;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShNoticeCollectorTest {

    private final ShNoticeCollector collector = new ShNoticeCollector(
            null, null, new ShCollectorProperties(true, null, 1, 365, 0L),
            new ShApplicationPeriodParser()
    );

    @Test
    void includesRecruitmentNoticesAndExcludesResults() {
        String html = """
                <table id="listTb"><tbody>
                  <tr><td>1730</td><td><a onclick="getDetailView('311730')">예비 4차 입주대상자 발표</a></td><td>2026-10-10</td></tr>
                  <tr><td>1729</td><td><a onclick="getDetailView('311729')">예비5차 당첨자 발표</a></td><td>2026-10-09</td></tr>
                  <tr><td>1726</td><td><a onclick="getDetailView('311726')">잔여세대 입주자 모집 공고</a></td><td>2026-10-08</td></tr>
                  <tr><td>1725</td><td><a onclick="getDetailView('311725')">신혼·신생아 매입임대주택 입주자 모집공고</a></td><td>2026-09-30</td></tr>
                  <tr><td>1724</td><td><a onclick="getDetailView('311724')">최종 경쟁률 안내</a></td><td>2026-09-29</td></tr>
                  <tr><td>1723</td><td><a onclick="getDetailView('311723')">입주자 모집공고 청약접수 결과안내</a></td><td>2026-09-28</td></tr>
                  <tr><td>1722</td><td><a onclick="getDetailView('311722')">입주자 모집공고 면접평가 대상자 발표</a></td><td>2026-09-27</td></tr>
                </tbody></table>
                """;

        var result = collector.parseList(
                html,
                URI.create("https://www.i-sh.co.kr/app/lay2/program/S48T1581C563/www/brd/m_247/list.do?multi_itm_seq=2"),
                LocalDate.of(2026, 1, 1)
        );

        assertThat(result).extracting(ShNoticeItem::sequence)
                .containsExactly("311726", "311725");
        assertThat(result.getFirst().detailUrl()).contains("view.do?multi_itm_seq=2&seq=311726");
    }

    @Test
    void excludesSaleNotice() {
        String html = """
                <table id="listTb"><tbody>
                  <tr><td>10</td><td><a onclick="getDetailView('311656')">토지임대부 분양주택 입주자모집공고</a></td><td>2026-10-16</td></tr>
                </tbody></table>
                """;

        var result = collector.parseList(
                html,
                URI.create("https://www.i-sh.co.kr/board/list.do?multi_itm_seq=1"),
                LocalDate.of(2026, 1, 1)
        );

        assertThat(result).isEmpty();
    }

    @Test
    void doesNotCollectNoticeWhoseApplicationPeriodHasEnded() {
        String listHtml = """
                <table id="listTb"><tbody>
                  <tr><td>1725</td><td><a onclick="getDetailView('311725')">청년 매입임대주택 입주자 모집공고</a></td><td>2026-01-01</td></tr>
                </tbody></table>
                """;
        String detailHtml = """
                <p>○ 인터넷 청약신청 : 2026. 1. 12.(월) 10:00 ~ 1. 14.(수) 17:00</p>
                """;
        ExternalDocumentClient client = new ExternalDocumentClient() {
            @Override
            public String getText(URI uri) {
                return uri.getPath().endsWith("list.do") ? listHtml : detailHtml;
            }
        };
        ShNoticeCollector endedNoticeCollector = new ShNoticeCollector(
                client,
                new HousingDetailEnricher(List.of()),
                new ShCollectorProperties(true, "https://example.com/list.do", 1, 3650, 0L),
                new ShApplicationPeriodParser()
        );

        assertThat(endedNoticeCollector.collect()).isEmpty();
    }

    @Test
    void collectsNoticeEvenWhenApplicationEndDateIsMissing() {
        String listHtml = """
                <table id="listTb"><tbody>
                  <tr><td>1725</td><td><a onclick="getDetailView('311725')">청년 매입임대주택 입주자 모집공고</a></td><td>2099-01-01</td></tr>
                </tbody></table>
                """;
        ExternalDocumentClient client = new ExternalDocumentClient() {
            @Override
            public String getText(URI uri) {
                return uri.getPath().endsWith("list.do") ? listHtml : "<p>접수기간은 추후 안내합니다.</p>";
            }
        };
        ShNoticeCollector noEndDateCollector = new ShNoticeCollector(
                client,
                new HousingDetailEnricher(List.of()),
                new ShCollectorProperties(true, "https://example.com/list.do", 1, 3650, 0L),
                new ShApplicationPeriodParser()
        );

        var commands = noEndDateCollector.collect();

        assertThat(commands).hasSize(1);
        assertThat(commands.getFirst().notice().applicationEndDate()).isNull();
    }
}
