package com.zipick.notice.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class NoticeTest {

    @Test
    void resetsMissingCountWhenNoticeIsSeenAgain() {
        Notice notice = new Notice("MYHOME", "20901", "행복주택 모집공고", NoticeStatus.OPEN);
        notice.markMissing(LocalDate.of(2026, 10, 10), 2);
        notice.markMissing(LocalDate.of(2026, 10, 11), 2);

        Instant seenAt = Instant.parse("2026-10-12T00:00:00Z");
        notice.markSeen(seenAt);

        assertThat(notice.getMissingCount()).isZero();
        assertThat(notice.getLastSeenAt()).isEqualTo(seenAt);
    }

    @Test
    void countsMissingOnlyOnceOnSameDay() {
        Notice notice = new Notice("MYHOME", "20901", "행복주택 모집공고", NoticeStatus.OPEN);
        LocalDate checkedOn = LocalDate.of(2026, 10, 12);

        notice.markMissing(checkedOn, 2);
        notice.markMissing(checkedOn, 2);

        assertThat(notice.getMissingCount()).isEqualTo(1);
        assertThat(notice.getStatus()).isEqualTo(NoticeStatus.OPEN);
    }

    @Test
    void closesOpenNoticeAfterTwoDailyMisses() {
        Notice notice = new Notice("MYHOME", "20901", "행복주택 모집공고", NoticeStatus.OPEN);

        notice.markMissing(LocalDate.of(2026, 10, 11), 2);
        boolean closed = notice.markMissing(LocalDate.of(2026, 10, 12), 2);

        assertThat(closed).isTrue();
        assertThat(notice.getStatus()).isEqualTo(NoticeStatus.CLOSED);
        assertThat(notice.getStatusReason()).isEqualTo(NoticeStatusReason.SOURCE_MISSING);
    }

    @Test
    void doesNotTurnClosedNoticeIntoCancelledNoticeWhenMissing() {
        Notice notice = new Notice("MYHOME", "20901", "행복주택 모집공고", NoticeStatus.CLOSED);

        notice.markMissing(LocalDate.of(2026, 10, 11), 2);
        notice.markMissing(LocalDate.of(2026, 10, 12), 2);

        assertThat(notice.getMissingCount()).isZero();
        assertThat(notice.getStatus()).isEqualTo(NoticeStatus.CLOSED);
    }

    @Test
    void recordsWhyStatusWasChanged() {
        Notice notice = new Notice("MYHOME", "20901", "행복주택 모집공고", NoticeStatus.OPEN);

        notice.changeStatus(NoticeStatus.CANCELLED, NoticeStatusReason.SOURCE_MISSING);

        assertThat(notice.getStatus()).isEqualTo(NoticeStatus.CANCELLED);
        assertThat(notice.getStatusReason()).isEqualTo(NoticeStatusReason.SOURCE_MISSING);
    }
}
