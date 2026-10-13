package com.zipick.housing.dto;

import com.zipick.housing.domain.Agency;
import com.zipick.housing.domain.Announcement;
import com.zipick.housing.domain.AnnouncementStatus;
import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import com.zipick.housing.domain.SupplyHouse;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 전체 공급주택 목록의 한 건 (점수 없음). 지도 핀·목록 카드에 필요한 값만 담는다. */
public record HouseSummaryResponse(
        Long houseId,
        Long announcementId,
        String title,
        Agency agency,
        RentalType rentalType,
        AnnouncementStatus status,
        LocalDate closeDate,
        String address,
        String sigunguCode,
        BigDecimal lat,
        BigDecimal lng,
        boolean approximate,
        HousingType housingType,
        Integer builtYear,
        Integer floor,
        BigDecimal exclusiveArea,
        Integer deposit,
        Integer monthlyRent
) {
    public static HouseSummaryResponse from(SupplyHouse h) {
        Announcement a = h.getAnnouncement();
        return new HouseSummaryResponse(h.getId(), a.getId(), a.getTitle(), a.getAgency(), a.getRentalType(),
                a.getStatus(), a.getCloseDate(), h.getAddress(), h.getSigunguCode(), h.getLat(), h.getLng(),
                h.isApproximate(), h.getHousingType(), h.getBuiltYear(), h.getFloor(), h.getExclusiveArea(),
                h.getDeposit(), h.getMonthlyRent());
    }
}
