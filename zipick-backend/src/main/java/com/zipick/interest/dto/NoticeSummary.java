package com.zipick.interest.dto;

import com.zipick.housing.domain.RentalType;
import com.zipick.notice.domain.NoticeStatus;
import java.time.LocalDate;
import java.util.List;

/**
 * 공고 요약 (관심 공고 목록·일정·알림에 공통). 공고 수집 테이블(notice ⨝ agency ⨝ housing)에서 읽는다.
 * 날짜는 Asia/Seoul 기준 날짜로 바꿔 둔다.
 */
public record NoticeSummary(
        Long noticeId,
        String title,
        String noticeType,
        RentalType rentalType,
        NoticeStatus status,
        String agencyName,
        String detailUrl,
        LocalDate announcedAt,
        LocalDate applyStartAt,
        LocalDate applyEndAt,
        LocalDate winnerAnnouncedAt,
        long housingCount,
        /** 이 공고의 주택이 있는 "시도 시군구" 목록 (최대 5) */
        List<String> regions
) {}
