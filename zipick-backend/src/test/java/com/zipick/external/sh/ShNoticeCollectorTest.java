package com.zipick.external.sh;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ShNoticeCollectorTest {

    private final ShNoticeCollector collector = new ShNoticeCollector(
            null, null, new ShCollectorProperties(true, null, 1, 365, 0L)
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
}
