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
                "공고취소", today.minusDays(1), today.plusDays(3), today
        );

        assertThat(result.status()).isEqualTo(NoticeStatus.CANCELLED);
        assertThat(result.reason()).isEqualTo(NoticeStatusReason.SOURCE_STATUS);
    }

    @Test
    void closesNoticeAfterApplicationEndDate() {
        NoticeStatusEvaluator.StatusEvaluation result = evaluator.evaluate(
                "접수중", today.minusDays(3), today.minusDays(1), today
        );

        assertThat(result.status()).isEqualTo(NoticeStatus.CLOSED);
        assertThat(result.reason()).isEqualTo(NoticeStatusReason.DATE_RULE);
    }

    @Test
    void keepsNoticeOpenThroughItsEndDate() {
        NoticeStatusEvaluator.StatusEvaluation result = evaluator.evaluate(
                "접수중", today.minusDays(3), today, today
        );

        assertThat(result.status()).isEqualTo(NoticeStatus.OPEN);
    }

    @Test
    void marksFutureApplicationAsUpcoming() {
        NoticeStatusEvaluator.StatusEvaluation result = evaluator.evaluate(
                "공고중", today.plusDays(1), today.plusDays(3), today
        );

        assertThat(result.status()).isEqualTo(NoticeStatus.UPCOMING);
        assertThat(result.reason()).isEqualTo(NoticeStatusReason.DATE_RULE);
    }
}
