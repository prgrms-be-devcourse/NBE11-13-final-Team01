package com.zipick.recommendation.dto;

import com.zipick.housing.domain.Candidate;
import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import com.zipick.notice.domain.NoticeStatus;
import com.zipick.recommendation.engine.EligibilityResult;
import com.zipick.recommendation.engine.HouseScore;
import com.zipick.recommendation.engine.ItemScore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 추천 결과 한 건 = 주택 하나(대표 호실 기준). 금액은 만 원.
 * 항목별 점수를 함께 내려 프론트가 슬라이더 재계산에 쓸 수 있게 한다.
 *
 * @param houseId  주택(housing) id. 상세·피드백·두 집 비교가 이 id 를 쓴다
 * @param supplyId 대표 호실(housing_supply) id
 * @param complete 순위에 필요한 정보(비용·지역)가 다 있는지. false 면 '정보 부족' 묶음
 */
public record RecommendedHouseResponse(
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
        Integer floor,
        BigDecimal exclusiveArea,
        Integer roomCount,
        Integer deposit,
        Integer monthlyRent,
        Integer monthlyCost,
        int score,
        boolean complete,
        EligibilityResult.Status eligibility,
        /** 추천에서 제외됐다면 사유: ELIGIBILITY / MUST 항목 키. 아니면 null */
        String excludedBy,
        Map<String, Double> itemScores,
        List<ReasonResponse> reasons
) {
    public static RecommendedHouseResponse from(HouseScore s, double monthlyCost) {
        Candidate c = s.candidate();
        Map<String, Double> items = new LinkedHashMap<>();
        for (var e : s.itemScores().entrySet()) {
            ItemScore is = e.getValue();
            items.put(e.getKey(), is.available() ? is.score() : null);
        }
        return new RecommendedHouseResponse(
                c.housingId(), c.supplyId(), c.noticeId(), c.noticeTitle(), c.agencyCode(), c.agencyName(),
                c.rentalType(), c.noticeType(), c.status(), c.closeDate(), c.detailUrl(),
                c.housingName(), c.address(), c.province(), c.district(), c.lat(), c.lng(),
                c.housingType(), c.housingTypeRaw(), c.buildYear(), c.floor(), c.exclusiveArea(), c.roomCount(),
                c.deposit(), c.monthlyRent(),
                (c.deposit() == null && c.monthlyRent() == null) ? null : (int) Math.round(monthlyCost),
                s.score(), s.complete(), s.eligibility().status(),
                s.eligibility().excluded() ? "ELIGIBILITY" : s.excludedBy(),
                items,
                s.reasons().stream().map(ReasonResponse::from).toList());
    }
}
