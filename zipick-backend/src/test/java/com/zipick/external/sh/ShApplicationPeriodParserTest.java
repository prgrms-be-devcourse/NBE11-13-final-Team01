package com.zipick.external.sh;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ShApplicationPeriodParserTest {

    private final ShApplicationPeriodParser parser = new ShApplicationPeriodParser();

    @Test
    void readsSpacedReceptionLabelsWithoutUsingPostingExpiryOrWinnerDates() {
        String html = """
                <p>본 게시글은 26. 11. 25. 일까지 게시될 예정입니다.</p>
                <p>▷ 접 수 기 간 : 2026.10.26.(월) ~ 2026.11.02.(월) 까지</p>
                <p>▷ 대상자 발표 : 2026년 11월 03일</p>
                """;
        var period = parser.parse(html).orElseThrow();
        assertThat(period.startDate()).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(period.endDate()).isEqualTo(LocalDate.of(2026, 11, 2));
    }

    @Test
    void ignoresDatesFromMenusAndNeighboringNoticesWhenDetailContentExists() {
        String html = """
                <div>청약신청 2026.1.1. ~ 2026.1.2.</div>
                <div class="detailTable"><table><tr><td class="cont">
                ▷ 접 수 기 간 : 2026.09.28.(월) ~ 2026.10.05.(월)까지
                </td></tr></table></div>
                <div>다음글 접수기간 2026.12.01. ~ 2026.12.31.</div>
                """;
        var period = parser.parse(html).orElseThrow();
        assertThat(period.startDate()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(period.endDate()).isEqualTo(LocalDate.of(2026, 10, 5));
    }

    @Test
    void doesNotUseLaterDatesInTheSameSectionAsTheEndOfAnExplicitRange() {
        String html = """
                <p>■ 신청접수 안내</p>
                <p>○ 등기우편 접수 - 접수기간 : 2026. 8. 3.(월) ~ 8. 6.(목)</p>
                <p>※ 2026. 8. 6.(목) 우체국 소인분까지 인정</p>
                <p>○ 방문접수 - 각 유형별 신청기간 : 2026. 8. 3.(월)</p>
                <p>입주예정 : 2026.12.10.</p>
                """;
        var period = parser.parse(html).orElseThrow();
        assertThat(period.startDate()).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(period.endDate()).isEqualTo(LocalDate.of(2026, 8, 6));
    }

    @Test
    void readsTwoDigitYearsInDocumentScheduleTable() {
        String text = """
                Ⅰ. 모집일정
                모집공고 서류접수 자격심사 주택개방 대상자발표 계약체결
                26.10.02
                26.10.02 26.10.04 ~ 개별안내
                ~26.10.03
                Ⅱ. 공급주택
                신청기간 2018.12.11.
                """;
        var period = parser.parseDocumentText(text).orElseThrow();
        assertThat(period.startDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(period.endDate()).isEqualTo(LocalDate.of(2026, 10, 3));
    }

    @Test
    void readsFourDigitYearsInDocumentScheduleTable() {
        String text = "모집일정\n모집공고 ⇨ 서류접수 ⇨ 자격심사\n"
                + "2026.08.20. 2026.08.20. ~2026.08.26 약 8주 소요";
        var period = parser.parseDocumentText(text).orElseThrow();
        assertThat(period.startDate()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(period.endDate()).isEqualTo(LocalDate.of(2026, 8, 26));
    }

    @Test
    void readsScheduleAfterLongIntroAndIgnoresSubsequentDateRanges() {
        String text = "모집일정 " + "예비입주 대상자를 모집합니다. ".repeat(15)
                + " 모집공고 ⇨ 서류접수 ⇨ 자격심사 ⇨ 대상자발표 "
                + "26.07.30. 26.07.30. ~ 26.08.06. 18:00접수마감 "
                + "출생일 1986.01.01.~2007.01.01.";
        var period = parser.parseDocumentText(text).orElseThrow();
        assertThat(period.startDate()).isEqualTo(LocalDate.of(2026, 7, 30));
        assertThat(period.endDate()).isEqualTo(LocalDate.of(2026, 8, 6));
    }

    @Test
    void doesNotTreatTwoUnconnectedScheduleDatesAsARange() {
        String text = "모집일정\n모집공고 ⇨ 서류접수 ⇨ 자격심사\n"
                + "26.07.08. 26.07.13. 26.07.15.~ 개별안내";
        assertThat(parser.parseDocumentText(text)).isEmpty();
    }

    @Test
    void doesNotReadWinnerAndWaitlistExpiryAsApplicationDates() {
        String text = "청약 신청하며 주택형별 순위를 반영합니다. 당첨자를 선정합니다. "
                + "예비자 자격은 당첨자 발표일(예정 2026.6.18.)로부터 12개월까지 유지되고, "
                + "12개월 경과한 다음날(예정 2027.6.18.) 예비자 지위가 소멸됩니다.";
        assertThat(parser.parseDocumentText(text)).isEmpty();
    }

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
    void parsesPlainSubscriptionLabelAndDateContainingSpacesAroundDots() {
        String html = """
                <p>■ 모집공고일: 2026. 4. 29.(수)</p>
                <p>■ 청약신청</p>
                <p>○ 1순위 / 2순위: 2026. 5. 18.(월) 10:00 ~ 2026. 5 . 22.(금) 17:00</p>
                <p>○ 이메일 접수 후 02-2116-7122로 확인</p>
                <p>■ 서류심사대상자 발표: 2026. 5. 27.(수) 14시 이후</p>
                <p>■ 당첨자발표: 2026. 8. 26.(수) 16시 이후</p>
                """;

        ShApplicationPeriod period = parser.parse(html).orElseThrow();

        assertThat(period.startDate()).isEqualTo(LocalDate.of(2026, 5, 18));
        assertThat(period.endDate()).isEqualTo(LocalDate.of(2026, 5, 22));
        assertThat(period.isNotExpiredOn(LocalDate.of(2026, 10, 27))).isFalse();
    }

    @Test
    void doesNotTreatPhoneNumberAsApplicationEndDate() {
        String html = """
                <p>■ 접수기간 : 2026.10.23.(금) ~11.09.(월)</p>
                <p>○ 이메일 접수 후 02-2116-7122로 확인</p>
                <p>■ 당첨자발표 : 2026.12.23.(수)</p>
                """;

        ShApplicationPeriod period = parser.parse(html).orElseThrow();

        assertThat(period.startDate()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(period.endDate()).isEqualTo(LocalDate.of(2026, 11, 9));
    }

    @Test
    void stopsBeforeEligibilityAndAdjacentArticleDates() {
        String html = """
                <p>■ 신청기간 : 2026.3.10.(화) 00:00 ~ 3.24.(화) 24:00</p>
                <p>■ 신청자격 : 청년창업인</p>
                <p>■ 신청문의 : ☎02-2091-3824</p>
                <p>이전글 결과 발표 2026-03-13</p>
                <p>다음글 안내 2026-03-06</p>
                """;

        ShApplicationPeriod period = parser.parse(html).orElseThrow();

        assertThat(period.startDate()).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(period.endDate()).isEqualTo(LocalDate.of(2026, 3, 24));
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
