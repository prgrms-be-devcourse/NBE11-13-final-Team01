package com.zipick.housing.domain;

import com.zipick.notice.domain.NoticeStatus;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 추천 후보 = 공급 호실 하나. 공고 수집 테이블(notice ⨝ housing ⨝ housing_supply)을 읽어 만든 읽기 전용 값이다.
 * 금액은 만 원으로 바꿔 둔다(원 단위 원본을 반올림).
 *
 * 점수는 호실마다 매기고, 화면에서는 주택(housingId)으로 묶어 가장 높은 호실을 대표로 보여 준다.
 */
public record Candidate(
        Long supplyId,
        Long housingId,
        Long noticeId,
        String noticeTitle,
        String noticeType,
        RentalType rentalType,
        NoticeStatus status,
        LocalDate closeDate,
        String detailUrl,
        String agencyCode,
        String agencyName,
        String housingName,
        String address,
        String province,
        String district,
        BigDecimal lat,
        BigDecimal lng,
        Integer buildYear,
        String housingTypeRaw,
        HousingType housingType,
        BigDecimal exclusiveArea,
        Integer roomCount,
        Integer floor,
        Integer deposit,
        Integer monthlyRent,
        Integer supplyCount
) {
    /** 관심 지역과 비교하는 키. 예: "경상북도 구미시" */
    public String regionKey() {
        return regionKey(province, district);
    }

    public static String regionKey(String province, String district) {
        if (province == null && district == null) return null;
        if (province == null) return district;
        if (district == null) return province;
        return province + " " + district;
    }

    /** 원 → 만 원 (반올림) */
    public static Integer toManwon(Long won) {
        return won == null ? null : (int) Math.round(won / 10_000.0);
    }
}
