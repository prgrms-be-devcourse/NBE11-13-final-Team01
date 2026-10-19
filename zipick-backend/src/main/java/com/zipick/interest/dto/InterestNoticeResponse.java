package com.zipick.interest.dto;

import java.time.LocalDate;

/**
 * 관심 공고 한 건.
 *
 * @param dDay 접수 마감까지 남은 일수. 마감일이 없으면 null, 지났으면 음수
 */
public record InterestNoticeResponse(
        Long id,
        NoticeSummary notice,
        String memo,
        Integer dDay,
        LocalDate savedAt
) {}
