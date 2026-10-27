package com.zipick.external.sh;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ShApplicationPeriodParserTest {

    private final ShApplicationPeriodParser parser = new ShApplicationPeriodParser();

    @Test
    void parsesInternetApplicationPeriodWithOmittedSecondYear() {
        String html = """
                <div>○ 인터넷 청약신청 : 2026. 1. 12.(월) 10:00 ~ 1. 14.(수) 17:00</div>
                <div>○ 당첨자 및 예비자 발표 : 2026. 5. 22.(금)</div>
                """;

        ShApplicationPeriod period = parser.parse(html).orElseThrow();

        assertThat(period.startDate()).isEqualTo(LocalDate.of(2026, 1, 12));
        assertThat(period.endDate()).isEqualTo(LocalDate.of(2026, 1, 14));
        assertThat(period.isNotExpiredOn(LocalDate.of(2026, 10, 19))).isFalse();
    }

    @Test
    void parsesCurrentlyOpenReceptionPeriod() {
        String html = """
                <p>■ 접수기간 : 2026.10.08.(목) ~ 2026.10.22.(목) 15시까지</p>
                <p>■ 당첨자발표 : 2026.12.30.(수)</p>
                """;

        ShApplicationPeriod period = parser.parse(html).orElseThrow();

        assertThat(period.startDate()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(period.endDate()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(period.isNotExpiredOn(LocalDate.of(2026, 10, 19))).isTrue();
    }

    @Test
    void parsesShPeriodWhoseEndDateOmitsTheYear() {
        String html = """
                <p>■ 모집공고일 : 2026.10.23.(금)</p>
                <p>■ 접수기간 : 2026.10.23.(금) ~ 11.09.(월)</p>
                <p>■ 당첨자발표 : 2026.12.23.(수) 17시 예정</p>
                """;

        ShApplicationPeriod period = parser.parse(html).orElseThrow();

        assertThat(period.startDate()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(period.endDate()).isEqualTo(LocalDate.of(2026, 11, 9));
    }

    @Test
    void keepsAnnouncementBeforeItsApplicationStartWhenEndDateHasNotPassed() {
        String html = """
                <p>■ 접수기간 : 2026.10.26.(월) ~ 2026.10.30.(금)</p>
                """;

        ShApplicationPeriod period = parser.parse(html).orElseThrow();

        assertThat(period.isNotExpiredOn(LocalDate.of(2026, 10, 19))).isTrue();
    }

    @Test
    void preservesShParagraphBoundariesBeforeWinnerAnnouncement() {
        String html = """
                <td>
                  <p>■ 접수기간 : 2026.10.26.(월) ~ 2026.10.30.(금)</p>
                  <p>○ 전자우편접수 : applicant@example.com</p>
                  <p><br/></p>
                  <p>■ 당첨자발표 : 2026.12.30.(수) 홈페이지 게시</p>
                </td>
                """;

        ShApplicationPeriod period = parser.parse(html).orElseThrow();

        assertThat(period.startDate()).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(period.endDate()).isEqualTo(LocalDate.of(2026, 10, 30));
    }

    @Test
    void separatesReceptionFromWinnerDateEvenWhenHtmlBecomesOneLine() {
        String html = "<div>■ 접수기간 : 2026.10.26.(월) ~ 2026.10.30.(금) "
                + "○ 전자우편접수 : applicant@example.com "
                + "■ 당첨자발표 : 2026.12.30.(수)</div>";

        ShApplicationPeriod period = parser.parse(html).orElseThrow();

        assertThat(period.startDate()).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(period.endDate()).isEqualTo(LocalDate.of(2026, 10, 30));
    }

    @Test
    void usesFirstAndLastDatesFromMultiStepReceptionSchedule() {
        String html = """
                <p>■ 접수일
                사전청약 당첨자: 2026.10.26.(월) ~ 10.27.(화)
                특별공급: 2026.11.02.(월) ~ 11.03.(화)
                2순위자: 2026.11.06.(금)</p>
                """;

        ShApplicationPeriod period = parser.parse(html).orElseThrow();

        assertThat(period.startDate()).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(period.endDate()).isEqualTo(LocalDate.of(2026, 11, 6));
    }

    @Test
    void ignoresDocumentSubmissionAndContractPeriods() {
        String html = """
                <p>서류접수기간 : 2026.10.20. ~ 2026.10.22.</p>
                <p>계약기간 : 2026.11.01. ~ 2026.11.03.</p>
                """;

        assertThat(parser.parse(html)).isEmpty();
    }
}
