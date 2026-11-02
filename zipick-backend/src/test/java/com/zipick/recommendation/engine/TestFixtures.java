package com.zipick.recommendation.engine;

import com.zipick.housing.domain.Candidate;
import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import com.zipick.notice.domain.NoticeStatus;
import com.zipick.recommendation.config.RecommendationProperties;
import com.zipick.recommendation.config.RecommendationProperties.EligibilityRule;
import com.zipick.recommendation.config.RecommendationProperties.Fallback;
import com.zipick.recommendation.config.RecommendationProperties.RegionScores;
import com.zipick.user.domain.UserPreference;
import com.zipick.user.domain.UserProfile;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 엔진 단위 테스트용 고정 데이터. application.yml 의 값과 같게 맞춘다. */
/** 엔진·서비스 테스트가 함께 쓰는 고정 데이터 (서비스 테스트에서도 쓰도록 public) */
public final class TestFixtures {

    private TestFixtures() {}

    public static Map<String, Integer> baseWeights() {
        Map<String, Integer> w = new LinkedHashMap<>();
        w.put("cost", 30);
        w.put("region", 25);
        w.put("area", 15);
        w.put("rooms", 10);
        w.put("infra", 10);
        w.put("age", 5);
        w.put("type", 5);
        return w;
    }

    public static RecommendationProperties props() {
        return new RecommendationProperties(
                60, 30, 5, 5, 0.7, 3.0, baseWeights(), List.of("cost", "region"), List.of(), 0.3,
                new RegionScores(1.0, 0.8, 0.6), 10,
                Map.of(1, 14, 2, 26, 3, 36, 4, 43, 5, 46, 6, 55), 0.5, 350, 1500, 30,
                Map.of(1, 4_576_036L, 2, 6_452_897L, 3, 8_168_429L, 4, 8_802_202L, 5, 9_326_985L, 6, 9_906_263L,
                        7, 10_485_541L),
                Map.of(
                        RentalType.HAPPY, new EligibilityRule(19, 39, true, Map.of("1", 120, "2", 110, "default", 100),
                                null, 25100, 4542, true, "신혼부부 계층(혼인 7년 이내 또는 만 6세 이하 자녀) 해당 여부는 공고문 확인 필요",
                                null, null, null),
                        RentalType.NATIONAL, new EligibilityRule(null, null, true, Map.of("1", 90, "2", 80, "default", 70),
                                null, 34500, 4542, null, null, null, null, null),
                        RentalType.YOUTH_PURCHASE, new EligibilityRule(19, 39, true, Map.of("default", 100), 1, 25100, 4542,
                                true, null, true,
                                new Fallback(34500, "부모 소득을 합산하는 2순위로 신청할 수 있는지 공고문 확인 필요"), null),
                        RentalType.PURCHASE, new EligibilityRule(null, null, null, null, null, null, null, null, null, null, null,
                                "매입임대는 대상(일반·신혼·청년 등)마다 조건이 달라 공고문 확인 필요"),
                        RentalType.DEUNDEUN, new EligibilityRule(null, null, true, null, null, null, null, null, null, null, null, null),
                        RentalType.PERMANENT, new EligibilityRule(null, null, null, null, null, null, null, null, null, null, null,
                                "영구임대는 수급자·차상위 등 대상이 따로 있어 공고문 확인 필요")));
    }

    /** 27세 미혼 1인, 월 260만 원, 자산 2,500만 원, 무주택 */
    public static UserProfile youngSingle() {
        return profile(27, 1, 260, 2500);
    }

    public static UserProfile profile(int age, int household, Integer income, Integer assets) {
        return UserProfile.builder().userId(1L).birthDate(LocalDate.now().minusYears(age))
                .married(false).householdSize(household).monthlyIncome(income).totalAssets(assets).carValue(0)
                .homeless(true).build();
    }

    /** 서울 마포구 1순위, 서대문구 2순위, 은평구 3순위. 면적 20~40, 방 1개 이상, 예산 45 */
    public static UserPreference mapoPreference() {
        return UserPreference.builder().userId(1L)
                .regions("[{\"code\":\"서울특별시 마포구\",\"name\":\"마포구\",\"rank\":1},"
                        + "{\"code\":\"서울특별시 서대문구\",\"name\":\"서대문구\",\"rank\":2},"
                        + "{\"code\":\"서울특별시 은평구\",\"name\":\"은평구\",\"rank\":3}]")
                .depositMax(3000).rentMax(35)
                .areaMin(new BigDecimal("20")).areaMax(new BigDecimal("40"))
                .housingTypes("[\"APARTMENT\",\"MULTI_HOUSEHOLD\"]").rentalTypes("[]")
                .monthlyBudget(45).roomsMin(1)
                .build();
    }

    public static UserContext context() {
        return UserContext.of(youngSingle(), mapoPreference(), null, props().baseWeights());
    }

    public static Candidate candidate(long id, long noticeId, RentalType type, String district, HousingType housingType,
                               String area, Integer deposit, Integer rent, Integer rooms, Integer buildYear) {
        return new Candidate(id, id, noticeId, "테스트 공고", type == null ? null : type.getLabel(), type,
                NoticeStatus.OPEN, LocalDate.now().plusDays(10 + id), null, "LH", "LH",
                "테스트 주택", "서울특별시 " + district + " 어딘가", "서울특별시", district,
                null, null, buildYear, housingType == null ? null : housingType.getLabel(), housingType,
                area == null ? null : new BigDecimal(area), rooms, 3, deposit, rent, 1);
    }

    public static Candidate house(RentalType type, String district, HousingType housingType, String area,
                           Integer deposit, Integer rent) {
        return candidate(1, 1, type, district, housingType, area, deposit, rent, 1, null);
    }

    /** 마포구 다세대 29.4㎡ 방 1개, 보증금 1,500 / 월 32 → 월 환산 38.25 (이율 5%) */
    public static Candidate goodHouse() {
        return house(RentalType.YOUTH_PURCHASE, "마포구", HousingType.MULTI_HOUSEHOLD, "29.4", 1500, 32);
    }
}
