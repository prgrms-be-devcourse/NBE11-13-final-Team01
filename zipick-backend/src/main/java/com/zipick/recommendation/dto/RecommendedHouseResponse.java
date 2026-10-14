package com.zipick.recommendation.dto;

import com.zipick.housing.domain.Agency;
import com.zipick.housing.domain.Announcement;
import com.zipick.housing.domain.AnnouncementStatus;
import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import com.zipick.housing.domain.SupplyHouse;
import com.zipick.recommendation.engine.EligibilityResult;
import com.zipick.recommendation.engine.HouseScore;
import com.zipick.recommendation.engine.ItemScore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 추천 결과 한 건. 항목별 점수를 함께 내려 프론트가 슬라이더 재계산에 쓸 수 있게 한다. */
public record RecommendedHouseResponse(
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
        Integer monthlyRent,
        Integer monthlyCost,
        int score,
        EligibilityResult.Status eligibility,
        /** 추천에서 제외됐다면 사유: ELIGIBILITY / coverage / MUST 항목 키. 아니면 null */
        String excludedBy,
        Map<String, Double> itemScores,
        List<ReasonResponse> reasons
) {
    public static RecommendedHouseResponse from(HouseScore s, double monthlyCost) {
        SupplyHouse h = s.house();
        Announcement a = h.getAnnouncement();
        Map<String, Double> items = new LinkedHashMap<>();
        for (var e : s.itemScores().entrySet()) {
            ItemScore is = e.getValue();
            items.put(e.getKey(), is.available() ? is.score() : null);
        }
        return new RecommendedHouseResponse(
                h.getId(), a.getId(), a.getTitle(), a.getAgency(), a.getRentalType(), a.getStatus(),
                a.getCloseDate(), h.getAddress(), h.getSigunguCode(), h.getLat(), h.getLng(), h.isApproximate(),
                h.getHousingType(), h.getBuiltYear(), h.getFloor(), h.getExclusiveArea(),
                h.getDeposit(), h.getMonthlyRent(),
                (h.getDeposit() == null || h.getMonthlyRent() == null) ? null : (int) Math.round(monthlyCost),
                s.score(), s.eligibility().status(),
                s.eligibility().excluded() ? "ELIGIBILITY" : s.excludedBy(),
                items,
                s.reasons().stream().map(ReasonResponse::from).toList());
    }
}
