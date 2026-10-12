package com.zipick.recommendation.engine;

import com.zipick.housing.domain.Agency;
import com.zipick.housing.domain.Announcement;
import com.zipick.housing.domain.AnnouncementStatus;
import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.InfraSummary;
import com.zipick.housing.domain.RentalType;
import com.zipick.housing.domain.SupplyHouse;
import com.zipick.recommendation.config.RecommendationProperties;
import com.zipick.recommendation.config.RecommendationProperties.EligibilityRule;
import com.zipick.recommendation.config.RecommendationProperties.RegionScores;
import com.zipick.user.domain.UserPreference;
import com.zipick.user.domain.UserProfile;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/** 엔진 단위 테스트용 고정 데이터. application.yml 의 값과 같게 맞춘다. */
final class TestFixtures {

    private TestFixtures() {}

    static RecommendationProperties props() {
        return new RecommendationProperties(
                60, 30, 5, 5, 0.7, new BigDecimal("6.0"),
                Map.of("cost", 35, "region", 25, "area", 15, "infra", 15, "type", 10),
                0.3, new RegionScores(1.0, 0.8, 0.6), 10, 500, 1500, 0.6,
                Map.of(
                        RentalType.PURCHASE, new EligibilityRule(19, 39, true, 400, 29900, 3708, false),
                        RentalType.HAPPY, new EligibilityRule(19, 39, true, 400, 36100, 3708, true),
                        RentalType.DEUNDEUN, new EligibilityRule(null, null, true, null, null, null, null)));
    }

    /** 27세 미혼 1인, 월 260만 원, 자산 2,500만 원, 무주택 */
    static UserProfile youngSingle() {
        return UserProfile.builder().userId(1L).birthDate(LocalDate.now().minusYears(27)).job("사무직")
                .married(false).householdSize(1).monthlyIncome(260).totalAssets(2500).carValue(0).homeless(true)
                .build();
    }

    /** 마포구 1순위, 서대문구 2순위, 은평구 3순위. 보증금 1,000~3,000, 월세 0~35, 면적 20~40, 예산 45 */
    static UserPreference mapoPreference() {
        return UserPreference.builder().userId(1L)
                .regions("[{\"code\":\"11440\",\"name\":\"마포구\",\"rank\":1},"
                        + "{\"code\":\"11410\",\"name\":\"서대문구\",\"rank\":2},"
                        + "{\"code\":\"11380\",\"name\":\"은평구\",\"rank\":3}]")
                .depositMin(1000).depositMax(3000).rentMin(0).rentMax(35)
                .areaMin(new BigDecimal("20")).areaMax(new BigDecimal("40"))
                .housingTypes("[\"APARTMENT\",\"MULTI_HOUSEHOLD\"]").rentalTypes("[\"PURCHASE\",\"HAPPY\"]")
                .monthlyBudget(45)
                .build();
    }

    static UserContext context() {
        return UserContext.of(youngSingle(), mapoPreference(), null, props().baseWeights());
    }

    static Announcement announcement(RentalType type) {
        return Announcement.builder().externalId("X-" + type).agency(Agency.LH).rentalType(type)
                .title("테스트 공고").region("서울").status(AnnouncementStatus.RECEIVING)
                .noticeDate(LocalDate.now().minusDays(3)).closeDate(LocalDate.now().plusDays(10)).build();
    }

    static SupplyHouse house(RentalType type, String sigungu, HousingType housingType, String area,
                             Integer deposit, Integer rent) {
        return SupplyHouse.builder().announcement(announcement(type)).address("서울 어딘가")
                .sigunguCode(sigungu).lat(new BigDecimal("37.55")).lng(new BigDecimal("126.92"))
                .approximate(false).housingType(housingType).builtYear(2019).floor(3)
                .exclusiveArea(area == null ? null : new BigDecimal(area))
                .deposit(deposit).monthlyRent(rent).conversionRate(new BigDecimal("6.0"))
                .purchaseType("청년 매입임대").supplyCount(1).build();
    }

    /** 마포구 다세대 29.4㎡, 보증금 1,500 / 월 32 → 월 환산 39.5 */
    static SupplyHouse goodHouse() {
        return house(RentalType.PURCHASE, "11440", HousingType.MULTI_HOUSEHOLD, "29.4", 1500, 32);
    }

    static InfraSummary infra(SupplyHouse h, String nearestJson) {
        return InfraSummary.builder().supplyHouse(h).categoryCounts("{}").nearest(nearestJson).build();
    }
}
