package com.zipick.notice.dto;

import com.zipick.notice.domain.Housing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

public record MapHousingDetailResponse(
        Long housingId,
        String name,
        String address,
        String normalizedAddress,
        String lotAddress,
        String roadAddress,
        BigDecimal latitude,
        BigDecimal longitude,
        Integer buildYear,
        Integer floor,
        String heatingMethod,
        Integer totalHouseholdCount,
        Long noticeId,
        String noticeTitle,
        String noticeType,
        String rentalType,
        String status,
        /** 접수 시작일(한국 시간). 공고에 일정이 없으면 null */
        LocalDate applyStartAt,
        /** 접수 마감일(한국 시간). 공고에 일정이 없으면 null */
        LocalDate applyEndAt,
        String agencyName,
        String detailUrl,
        List<MapHousingSupplyResponse> supplies,
        List<MapHousingAttachmentResponse> attachments,
        List<MapHousingMediaResponse> media
) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public static MapHousingDetailResponse of(
            Housing housing,
            List<MapHousingSupplyResponse> supplies,
            List<MapHousingAttachmentResponse> attachments,
            List<MapHousingMediaResponse> media
    ) {
        var notice = housing.getNotice();
        return new MapHousingDetailResponse(
                housing.getId(), housing.getName(), housing.getAddress(), housing.getNormalizedAddress(),
                housing.getLotAddress(), housing.getRoadAddress(),
                housing.getLatitude(), housing.getLongitude(), housing.getBuildYear(), housing.getFloor(),
                housing.getHeatingMethod(), housing.getTotalHouseholdCount(), notice.getId(), notice.getTitle(),
                notice.getNoticeType(), notice.getRentalType(), notice.getStatus().name(),
                toSeoulDate(notice.getApplyStartAt()), toSeoulDate(notice.getApplyEndAt()),
                notice.getAgency() == null ? null : notice.getAgency().getName(), notice.getDetailUrl(),
                supplies, attachments, media
        );
    }

    /**
     * 접수 일정은 Instant 로 저장돼 있다. 날짜만 쓰는 화면(D-day·접수 기간)을 위해 한국 시간 기준 날짜로 바꾼다.
     * 전체 공고 목록의 closeDate(CandidateQueryRepository)와 같은 변환이라 두 화면의 D-day가 어긋나지 않는다.
     */
    private static LocalDate toSeoulDate(Instant instant) {
        return instant == null ? null : LocalDate.ofInstant(instant, SEOUL);
    }
}
