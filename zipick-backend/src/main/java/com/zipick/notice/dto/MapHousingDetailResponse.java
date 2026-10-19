package com.zipick.notice.dto;

import com.zipick.notice.domain.Housing;

import java.math.BigDecimal;
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
        String agencyName,
        String detailUrl,
        List<MapHousingSupplyResponse> supplies,
        List<MapHousingAttachmentResponse> attachments,
        List<MapHousingMediaResponse> media
) {
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
                notice.getAgency() == null ? null : notice.getAgency().getName(), notice.getDetailUrl(),
                supplies, attachments, media
        );
    }
}
