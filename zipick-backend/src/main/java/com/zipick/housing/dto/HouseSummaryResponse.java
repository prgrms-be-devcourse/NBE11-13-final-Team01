package com.zipick.housing.dto;

import com.zipick.housing.domain.Candidate;
import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import com.zipick.notice.domain.NoticeStatus;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 전체 공급주택 목록의 한 건 = 주택 하나(점수 없음). 대표 호실의 값을 보여 준다. 금액은 만 원.
 *
 * @param supplyUnits 이 주택에서 필터를 통과한 공급 호실 수
 */
public record HouseSummaryResponse(
        Long houseId,
        Long supplyId,
        Long announcementId,
        String title,
        String agencyCode,
        String agencyName,
        RentalType rentalType,
        String noticeType,
        NoticeStatus status,
        LocalDate closeDate,
        String detailUrl,
        String housingName,
        String address,
        String province,
        String district,
        BigDecimal lat,
        BigDecimal lng,
        HousingType housingType,
        String housingTypeRaw,
        Integer buildYear,
        BigDecimal exclusiveArea,
        Integer roomCount,
        Integer deposit,
        Integer monthlyRent,
        int supplyUnits
) {
    public static HouseSummaryResponse from(Candidate c, int supplyUnits) {
        return new HouseSummaryResponse(c.housingId(), c.supplyId(), c.noticeId(), c.noticeTitle(), c.agencyCode(),
                c.agencyName(), c.rentalType(), c.noticeType(), c.status(), c.closeDate(), c.detailUrl(),
                c.housingName(), c.address(), c.province(), c.district(), c.lat(), c.lng(),
                c.housingType(), c.housingTypeRaw(), c.buildYear(), c.exclusiveArea(), c.roomCount(),
                c.deposit(), c.monthlyRent(), supplyUnits);
    }
}
