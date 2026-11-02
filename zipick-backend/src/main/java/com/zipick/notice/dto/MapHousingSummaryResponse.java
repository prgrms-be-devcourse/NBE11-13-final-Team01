package com.zipick.notice.dto;

import com.zipick.notice.domain.Housing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 지도 핀 하나. 핀 표시에 필요한 최소 정보만 담는다(보증금·면적 같은 공급 정보는 상세 조회에서).
 *
 * <p>province/district 는 프론트의 행정구역별 군집화가 쓴다. 주소 문자열을 프론트에서 쪼개면
 * "서울특별시"/"서울시", "안양시 동안구" 같은 변형에 부정확해지므로 이미 정규화된 값을 그대로 내려준다
 * (docs/map-clustering.md). {@link Housing} 에 이미 있는 값이라 추가 조회가 생기지 않는다.
 * 주소 정규화가 안 된 주택은 둘 중 하나 또는 둘 다 null 일 수 있다.
 *
 * <p>noticeId·noticeAnnouncedAt·applyEndAt 는 지도에서 <b>같은 좌표에 겹친 주택</b>을 다룰 때 쓴다.
 * 같은 건물이라도 공고가 다르면 서로 다른 신청 기회이므로 합치면 안 되는데, 사용자가 목록에서
 * 그 차이를 알아볼 수 있어야 한다. 실제로 같은 건물에 몇 주 간격으로 올라온 공고가 제목·기관·유형까지
 * 모두 같은 경우가 있어, 그때는 <b>공고일이 유일한 구분자</b>가 된다
 * (features/map/locationGroup.ts, LocationList).
 *
 * <p>⚠️ noticeId 는 관심 공고 저장에 쓰는 바로 그 id 다. housingId 와 혼동하지 말 것.
 */
public record MapHousingSummaryResponse(
        Long housingId,
        String name,
        String address,
        String province,
        String district,
        BigDecimal latitude,
        BigDecimal longitude,
        Long noticeId,
        String noticeTitle,
        /** 공고일. Notice 가 이미 LocalDate 로 들고 있어 시간대 변환이 필요 없다. 없으면 null */
        LocalDate noticeAnnouncedAt,
        /** 접수 마감일 (한국 시간 기준 날짜). 공고에 일정이 없으면 null */
        LocalDate applyEndAt,
        String agencyName,
        String rentalType,
        String status
) {
    public static MapHousingSummaryResponse from(Housing housing) {
        var notice = housing.getNotice();
        return new MapHousingSummaryResponse(
                housing.getId(),
                housing.getName(),
                housing.getAddress(),
                housing.getProvinceName(),
                housing.getDistrictName(),
                housing.getLatitude(),
                housing.getLongitude(),
                notice.getId(),
                notice.getTitle(),
                notice.getAnnouncedAt(),
                toSeoulDate(notice.getApplyEndAt()),
                notice.getAgency() == null ? null : notice.getAgency().getName(),
                notice.getRentalType(),
                notice.getStatus().name()
        );
    }

    /** UTC 로 자르면 날짜가 하루 밀린다. 상세 응답과 같은 규칙을 쓴다 */
    private static LocalDate toSeoulDate(Instant instant) {
        return instant == null ? null : LocalDate.ofInstant(instant, ZoneId.of("Asia/Seoul"));
    }
}
