package com.zipick.notice.dto;

import com.zipick.notice.domain.Housing;

import java.math.BigDecimal;

public record MapHousingSummaryResponse(
        Long housingId,
        String name,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        String noticeTitle,
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
                housing.getLatitude(),
                housing.getLongitude(),
                notice.getTitle(),
                notice.getAgency() == null ? null : notice.getAgency().getName(),
                notice.getRentalType(),
                notice.getStatus().name()
        );
    }
}
