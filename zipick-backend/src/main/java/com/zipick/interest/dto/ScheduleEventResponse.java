package com.zipick.interest.dto;

import java.time.LocalDate;

/**
 * 캘린더 일정 하나. 관심 공고의 날짜(공고일·접수 시작·접수 마감·당첨 발표)마다 하나씩 만든다.
 *
 * @param kind ANNOUNCED / APPLY_START / APPLY_END / WINNER
 */
public record ScheduleEventResponse(
        String id,
        Long noticeId,
        String title,
        Kind kind,
        LocalDate date,
        Integer dDay,
        String detailUrl
) {
    public enum Kind {
        ANNOUNCED("공고"), APPLY_START("접수 시작"), APPLY_END("접수 마감"), WINNER("당첨 발표");

        private final String label;
        Kind(String label) { this.label = label; }
        public String getLabel() { return label; }
    }
}
