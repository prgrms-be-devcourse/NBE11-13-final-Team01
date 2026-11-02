package com.zipick.external.gh;

import com.zipick.external.housingdetail.ExternalDocumentClient;
import com.zipick.external.housingdetail.HousingDetailEnricher;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GhNoticeCollectorTest {

    private final GhNoticeCollector collector = new GhNoticeCollector(
            null, null, new GhCollectorProperties(true, 1, 0L)
    );

    @Test
    void parsesGhListRowAndBuildsDetailUrl() {
        String html = """
                <div id="sub_content"><table><tbody><tr>
                  <td>1</td><td>행복주택</td>
                  <td><a class="text_cut" data-pbancNo="839" data-pbancKndCd="01"
                         data-bizTyNm="행복주택">하반기 예비입주자 추가모집 공고</a></td>
                  <td>성남시</td><td>첨부</td><td>2026-10-16</td><td>2026-10-28</td><td>공고중</td>
                </tr></tbody></table></div>
                """;

        var result = collector.parseList(html, URI.create(
                "https://apply.gh.or.kr/sb/sr/sr7150/selectPbancRentHouseList.do"));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().noticeId()).isEqualTo("839");
        assertThat(result.getFirst().applyEndAt()).isEqualTo(LocalDate.of(2026, 10, 28));
        assertThat(result.getFirst().detailUrl())
                .contains("selectPbancDetailView.do?pbancNo=839")
                .contains("bizTyNm=%ED%96%89%EB%B3%B5%EC%A3%BC%ED%83%9D");
    }

    @Test
    void readsApplicationPeriodFromDetail() {
        String html = """
                <li><b>온라인접수기간 : </b>2026.10.26 10:00 ~ 2026.10.28 17:00</li>
                """;

        GhNoticeCollector.DateRange period = collector.parsePeriod(html);

        assertThat(period.start()).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(period.end()).isEqualTo(LocalDate.of(2026, 10, 28));
    }

    @Test
    void keepsKnownStartForOpenEndedApplicationPeriod() {
        GhNoticeCollector.DateRange period = collector.parsePeriod(
                "<li>온라인접수기간 : 2026.09.01 09:00 ~</li>");

        assertThat(period.start()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(period.end()).isNull();
    }

    @Test
    void collectsActiveNoticeFromBothListsAndEnrichesIt() {
        ExternalDocumentClient client = mock(ExternalDocumentClient.class);
        HousingDetailEnricher enricher = mock(HousingDetailEnricher.class);
        GhNoticeCollector activeCollector = new GhNoticeCollector(
                client, enricher, new GhCollectorProperties(true, 1, 0L)
        );
        String activeList = row("839", "2099-10-16", "2099-10-28", "공고중");
        String closedList = row("900", "2020-10-16", "2020-10-28", "마감");
        when(client.getText(any())).thenAnswer(invocation -> {
            URI uri = invocation.getArgument(0);
            if (uri.getPath().contains("7150") && uri.getPath().contains("List")) return activeList;
            if (uri.getPath().contains("7155") && uri.getPath().contains("List")) return closedList;
            return "<li><b>접수기간:</b>2099.10.20 ~ 2099.10.28</li>";
        });
        when(enricher.enrich(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var commands = activeCollector.collect();

        assertThat(commands).hasSize(1);
        assertThat(commands.getFirst().notice().source()).isEqualTo("GH");
        assertThat(commands.getFirst().notice().applicationStartDate()).isEqualTo("2099-10-20");
        assertThat(commands.getFirst().rawPayload()).contains("839");
    }

    @Test
    void collectsNoticeWithoutApplicationEndDate() {
        ExternalDocumentClient client = mock(ExternalDocumentClient.class);
        HousingDetailEnricher enricher = mock(HousingDetailEnricher.class);
        GhNoticeCollector noEndDateCollector = new GhNoticeCollector(
                client, enricher, new GhCollectorProperties(true, 1, 0L)
        );
        String noEndDateList = row("811", "2099-09-01", "-", "공고중");
        when(client.getText(any())).thenAnswer(invocation -> {
            URI uri = invocation.getArgument(0);
            if (uri.getPath().contains("7150") && uri.getPath().contains("List")) return noEndDateList;
            if (uri.getPath().contains("7155") && uri.getPath().contains("List")) return "";
            return "<p>접수기간은 별도 안내합니다.</p>";
        });
        when(enricher.enrich(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var commands = noEndDateCollector.collect();

        assertThat(commands).hasSize(1);
        assertThat(commands.getFirst().notice().applicationEndDate()).isNull();
    }

    @Test
    void supportsDisabledCollectorFallbackPeriodAndMalformedRows() {
        GhNoticeCollector disabled = new GhNoticeCollector(
                null, null, new GhCollectorProperties(false, 1, 0L)
        );
        assertThat(disabled.collect()).isEmpty();

        assertThat(collector.parseList("<table><tr><td>짧음</td></tr></table>",
                URI.create("https://apply.gh.or.kr/list.do"))).isEmpty();
        assertThat(collector.parsePeriod("접수기간 정보 없음")).isNull();
        assertThat(collector.parsePeriod("접수기간: 2099.13.40 ~ 2099.14.50")).isNull();
    }

    private String row(String id, String announced, String end, String status) {
        return """
                <div id="sub_content"><table><tbody><tr>
                  <td>1</td><td>행복주택</td>
                  <td><a class="text_cut" data-pbancNo="%s" data-pbancKndCd="01"
                         data-bizTyNm="행복주택">예비입주자 모집 공고</a></td>
                  <td>성남시</td><td>첨부</td><td>%s</td><td>%s</td><td>%s</td>
                </tr></tbody></table></div>
                """.formatted(id, announced, end, status);
    }
}
