package com.zipick.notice.service;

import com.zipick.notice.domain.NoticeStatus;
import com.zipick.notice.domain.NoticeStatusReason;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.Locale;

@Component
public class NoticeStatusEvaluator {

    public StatusEvaluation evaluate(
            String rawStatus,
            String title,
            LocalDate applicationStartDate,
            LocalDate applicationEndDate,
            LocalDate today
    ) {
        String normalizedStatus = normalize(rawStatus);
        String normalizedTitle = normalize(title);

        if (normalizedStatus.contains("취소") || normalizedTitle.contains("공고취소")) {
            return new StatusEvaluation(NoticeStatus.CANCELLED, NoticeStatusReason.SOURCE_STATUS);
        }
        if (containsAny(normalizedTitle,
                "조기접수마감", "조기신청마감", "조기모집마감", "조기마감",
                "접수마감", "신청마감", "모집마감")) {
            return new StatusEvaluation(NoticeStatus.CLOSED, NoticeStatusReason.SOURCE_STATUS);
        }
        if (applicationEndDate != null && applicationEndDate.isBefore(today)) {
            return new StatusEvaluation(NoticeStatus.CLOSED, NoticeStatusReason.DATE_RULE);
        }
        if (containsAny(normalizedStatus, "마감", "종료", "완료")) {
            return new StatusEvaluation(NoticeStatus.CLOSED, NoticeStatusReason.SOURCE_STATUS);
        }
        if (applicationEndDate == null && containsAny(normalizedStatus, "예정", "공고전")) {
            return new StatusEvaluation(NoticeStatus.UPCOMING, NoticeStatusReason.SOURCE_STATUS);
        }
        return new StatusEvaluation(NoticeStatus.OPEN, NoticeStatusReason.SOURCE_STATUS);
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    public record StatusEvaluation(NoticeStatus status, NoticeStatusReason reason) {
    }
}
