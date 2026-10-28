package com.zipick.external.hug;

import com.zipick.external.housingdetail.ExternalDocumentClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HugNoticeCollectorTest {

    private final HugNoticeCollector collector = new HugNoticeCollector(
            null, new HugCollectorProperties(true, "https://example.com/list", 1, 0L)
    );

    @Test
    void parsesSupplyRowsAndAnnouncementAttachment() {
        String html = """
                <a href="/files/notice.pdf">모집공고</a>
                <table class="mb d_list"><tbody><tr>
                  <td>1</td><td>2026.10.16</td><td>2026.10.16 ~ 2026.10.30</td>
                  <td>서울특별시</td><td>강서구</td><td>서울특별시 강서구 공항대로 1</td>
                  <td>아파트</td><td>신축매입</td><td>59.80</td><td>180,000,000원</td><td>3</td>
                </tr></tbody></table>
                """;

        var rows = collector.parseRows(html);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().period().end()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(rows.getFirst().exclusiveArea()).isEqualByComparingTo(new BigDecimal("59.80"));
        assertThat(rows.getFirst().deposit()).isEqualTo(180_000_000L);
        assertThat(rows.getFirst().attachments()).hasSize(1);
    }

    @Test
    void acceptsOfficialEmptyTableAsSuccessfulCollection() {
        String html = """
                <div>총 0건</div>
                <table class="mb d_list"><tbody></tbody></table>
                <p>등록된 게시물이 없습니다.</p>
                """;
        ExternalDocumentClient client = new ExternalDocumentClient() {
            @Override public String getText(URI uri) { return html; }
        };
        HugNoticeCollector emptyCollector = new HugNoticeCollector(
                client, new HugCollectorProperties(true, "https://example.com/list", 1, 0L)
        );

        assertThat(emptyCollector.collect()).isEmpty();
    }

    @Test
    void parsesRowWithoutApplicationEndDate() {
        String html = """
                <table class="mb d_list"><tbody><tr>
                  <td>1</td><td>2099.10.16</td><td>2099.10.16 ~</td>
                  <td>서울특별시</td><td>강서구</td><td>서울특별시 강서구 공항대로 1</td>
                  <td>아파트</td><td>신축매입</td><td>59.80</td><td>180,000,000원</td><td>3</td>
                </tr></tbody></table>
                """;

        var rows = collector.parseRows(html);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().period().start()).isEqualTo(LocalDate.of(2099, 10, 16));
        assertThat(rows.getFirst().period().end()).isNull();
    }

    @Test
    void collectsNoticeWithoutApplicationEndDate() {
        String html = """
                <table class="mb d_list"><tbody><tr>
                  <td>1</td><td>2099.10.16</td><td>2099.10.16 ~</td>
                  <td>서울</td><td>강서구</td><td>서울 강서구 공항대로 1</td>
                  <td>아파트</td><td>신축매입</td><td>59.8</td><td>180,000,000원</td><td>3</td>
                </tr></tbody></table>
                """;
        ExternalDocumentClient client = new ExternalDocumentClient() {
            @Override public String getText(URI uri) { return html; }
        };
        HugNoticeCollector noEndDateCollector = new HugNoticeCollector(
                client, new HugCollectorProperties(true, "https://example.com/list", 1, 0L)
        );

        var commands = noEndDateCollector.collect();

        assertThat(commands).hasSize(1);
        assertThat(commands.getFirst().notice().applicationEndDate()).isNull();
    }

    @Test
    void collectsAndGroupsActiveRowsIntoOneNotice() {
        String html = """
                <a href="/files/notice.pdf">모집공고</a>
                <a href="/files/notice.pdf">중복</a>
                <table class="mb d_list"><tbody>
                <tr><td>1</td><td>2099.10.16</td><td>2099.10.16 ~ 2099.10.30</td>
                  <td>서울</td><td>강서구</td><td>서울 강서구 공항대로 1</td>
                  <td>아파트</td><td>신축매입</td><td>59.8㎡</td><td>180,000,000원</td><td>3명</td></tr>
                <tr><td>2</td><td>2099.10.16</td><td>2099.10.16 ~ 2099.10.30</td>
                  <td>서울</td><td>강서구</td><td>서울 강서구 공항대로 2</td>
                  <td>아파트</td><td>신축매입</td><td>잘못된면적</td><td>-</td></tr>
                </tbody></table>
                """;
        ExternalDocumentClient client = new ExternalDocumentClient() {
            @Override public String getText(URI uri) { return html; }
        };
        HugNoticeCollector active = new HugNoticeCollector(
                client, new HugCollectorProperties(true, "https://example.com/list?type=1", 2, 0L)
        );
        var history = org.mockito.Mockito.mock(com.zipick.notice.repository.DocumentParseHistoryRepository.class);
        active.setHistoryRepository(history);

        var commands = active.collect();

        assertThat(commands).hasSize(1);
        assertThat(commands.getFirst().notice().houses()).hasSize(4);
        var checkpoint = commands.getFirst().notice().parseCheckpoints().getFirst();
        var success = new com.zipick.notice.domain.DocumentParseHistory(checkpoint.documentKey(), checkpoint.parserVersion());
        success.markSuccess(checkpoint.size(), checkpoint.unitCount());
        org.mockito.Mockito.when(history.findByDocumentKeyAndParserVersion(checkpoint.documentKey(), checkpoint.parserVersion()))
                .thenReturn(java.util.Optional.of(success));
        assertThat(active.collect().getFirst().notice().preservesExistingHousings()).isTrue();
        assertThat(commands.getFirst().notice().attachments()).hasSize(1);
        assertThat(commands.getFirst().rawPayload()).contains("공항대로");
    }

    @Test
    void rejectsUnknownEmptyPageAndSupportsDisabledModeAndInvalidRows() {
        HugNoticeCollector disabled = new HugNoticeCollector(
                null, new HugCollectorProperties(false, "https://example.com/list", 1, 0L)
        );
        assertThat(disabled.collect()).isEmpty();

        ExternalDocumentClient client = new ExternalDocumentClient() {
            @Override public String getText(URI uri) { return "<html>점검 중</html>"; }
        };
        HugNoticeCollector invalid = new HugNoticeCollector(
                client, new HugCollectorProperties(true, "https://example.com/list", 1, 0L)
        );
        assertThatThrownBy(invalid::collect).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("확인할 수 없습니다");

        assertThat(collector.parseRows("""
                <table class="d_list"><tbody>
                  <tr><td>짧음</td></tr>
                  <tr><td>1</td><td>잘못된날짜</td><td>날짜없음</td><td></td><td></td>
                  <td></td><td></td><td></td><td></td><td></td></tr>
                </tbody></table>
                """)).isEmpty();
    }
}
