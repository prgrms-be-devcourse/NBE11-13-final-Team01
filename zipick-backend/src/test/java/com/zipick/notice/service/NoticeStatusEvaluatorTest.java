package com.zipick.notice.service;

import com.zipick.notice.domain.NoticeStatus;
import com.zipick.notice.domain.NoticeStatusReason;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class NoticeStatusEvaluatorTest {

    private final NoticeStatusEvaluator evaluator = new NoticeStatusEvaluator();
    private final LocalDate today = LocalDate.of(2026, 10, 13);

    @Test
    void prioritizesExplicitCancellation() {
        NoticeStatusEvaluator.StatusEvaluation result = evaluator.evaluate(
                "공고취소", "행복주택 모집공고", today.minusDays(1), today.plusDays(3), today
        );

        assertThat(result.status()).isEqualTo(NoticeStatus.CANCELLED);
        assertThat(result.reason()).isEqualTo(NoticeStatusReason.SOURCE_STATUS);
    }

    @Test
    void closesNoticeAfterApplicationEndDate() {
        NoticeStatusEvaluator.StatusEvaluation result = evaluator.evaluate(
                "접수중", "행복주택 모집공고", today.minusDays(3), today.minusDays(1), today
        );

        assertThat(result.status()).isEqualTo(NoticeStatus.CLOSED);
        assertThat(result.reason()).isEqualTo(NoticeStatusReason.DATE_RULE);
    }

    @Test
    void keepsNoticeOpenThroughItsEndDate() {
        NoticeStatusEvaluator.StatusEvaluation result = evaluator.evaluate(
                "접수중", "행복주택 모집공고", today.minusDays(3), today, today
        );

        assertThat(result.status()).isEqualTo(NoticeStatus.OPEN);
    }

    @Test
    void keepsFutureApplicationOpenWhenItsEndDateHasNotPassed() {
        NoticeStatusEvaluator.StatusEvaluation result = evaluator.evaluate(
                "공고중", "행복주택 모집공고", today.plusDays(1), today.plusDays(3), today
        );

        assertThat(result.status()).isEqualTo(NoticeStatus.OPEN);
        assertThat(result.reason()).isEqualTo(NoticeStatusReason.SOURCE_STATUS);
    }

    @Test
    void closesEarlyClosedNoticeFromTitleRegardlessOfSource() {
        NoticeStatusEvaluator.StatusEvaluation result = evaluator.evaluate(
                "정정공고", "[조기접수마감] 행복주택 입주자 모집",
                today.minusDays(1), today.plusMonths(1), today
        );

        assertThat(result.status()).isEqualTo(NoticeStatus.CLOSED);
        assertThat(result.reason()).isEqualTo(NoticeStatusReason.SOURCE_STATUS);
    }

    @Test
    void keepsNoticeWithoutEndDateOpen() {
        NoticeStatusEvaluator.StatusEvaluation result = evaluator.evaluate(
                "공고중", "상시모집 공고", today.minusDays(1), null, today
        );

        assertThat(result.status()).isEqualTo(NoticeStatus.OPEN);
    }
}
