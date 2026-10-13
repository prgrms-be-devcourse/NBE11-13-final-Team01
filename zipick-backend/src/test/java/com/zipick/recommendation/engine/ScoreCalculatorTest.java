package com.zipick.recommendation.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import com.zipick.housing.domain.SupplyHouse;
import com.zipick.recommendation.config.RecommendationProperties;
import com.zipick.recommendation.domain.RecommendationProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ScoreCalculatorTest {

    private final RecommendationProperties props = TestFixtures.props();
    private final ScoreCalculator calc = new ScoreCalculator(props, new EligibilityChecker(props));

    @Nested
    @DisplayName("비용 점수")
    class Cost {

        @Test
        @DisplayName("월 환산 비용 = 월세 + 보증금 × 이율 ÷ 12")
        void monthlyCost() {
            // 32 + 1500 × 0.06 / 12 = 32 + 7.5 = 39.5
            assertThat(calc.monthlyCost(TestFixtures.goodHouse())).isCloseTo(39.5, offset(0.001));
        }

        @Test
        @DisplayName("예산 이내면 1점")
        void withinBudget() {
            ItemScore s = calc.scoreCost(TestFixtures.context(), TestFixtures.goodHouse());
            assertThat(s.available()).isTrue();
            assertThat(s.score()).isEqualTo(1.0);
            assertThat(s.reason().mark()).isEqualTo(Mark.PASS);
        }

        @Test
        @DisplayName("예산을 15% 넘으면 0.5점, 30% 넘으면 0점")
        void overBudget() {
            // 예산 45. 월 환산 51.75 = 45 × 1.15 → 월세 44.25 + 보증금 1500(7.5)
            SupplyHouse h15 = TestFixtures.house(RentalType.PURCHASE, "11440", HousingType.MULTI_HOUSEHOLD,
                    "29", 1500, 44);
            double s15 = calc.scoreCost(TestFixtures.context(), h15).score();
            assertThat(s15).isCloseTo(0.5, offset(0.03));

            SupplyHouse h40 = TestFixtures.house(RentalType.PURCHASE, "11440", HousingType.MULTI_HOUSEHOLD,
                    "29", 1500, 60);
            assertThat(calc.scoreCost(TestFixtures.context(), h40).score()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("더 싼 집의 비용 점수가 더 낮을 수 없다 (단조성)")
        void monotonic() {
            UserContext ctx = TestFixtures.context();
            double prev = 2;
            for (int rent = 20; rent <= 70; rent += 5) {
                SupplyHouse h = TestFixtures.house(RentalType.PURCHASE, "11440", HousingType.MULTI_HOUSEHOLD,
                        "29", 1500, rent);
                double s = calc.scoreCost(ctx, h).score();
                assertThat(s).isLessThanOrEqualTo(prev);
                prev = s;
            }
        }

        @Test
        @DisplayName("보증금·월세가 없으면 계산 제외")
        void unavailable() {
            SupplyHouse h = TestFixtures.house(RentalType.PURCHASE, "11440", HousingType.MULTI_HOUSEHOLD,
                    "29", null, null);
            assertThat(calc.scoreCost(TestFixtures.context(), h).available()).isFalse();
        }
    }

    @Nested
    @DisplayName("지역·면적·유형 점수")
    class RegionAreaType {

        @Test
        void regionByRank() {
            UserContext ctx = TestFixtures.context();
            assertThat(calc.scoreRegion(ctx, TestFixtures.house(RentalType.PURCHASE, "11440", null, null, null, null)).score()).isEqualTo(1.0);
            assertThat(calc.scoreRegion(ctx, TestFixtures.house(RentalType.PURCHASE, "11410", null, null, null, null)).score()).isEqualTo(0.8);
            assertThat(calc.scoreRegion(ctx, TestFixtures.house(RentalType.PURCHASE, "11380", null, null, null, null)).score()).isEqualTo(0.6);
            assertThat(calc.scoreRegion(ctx, TestFixtures.house(RentalType.PURCHASE, "11500", null, null, null, null)).score()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("면적은 범위 안 1점, 10㎡ 벗어날 때마다 0.25 감점")
        void area() {
            UserContext ctx = TestFixtures.context(); // 20~40
            assertThat(calc.scoreArea(ctx, TestFixtures.house(RentalType.PURCHASE, "11440", null, "29.4", null, null)).score()).isEqualTo(1.0);
            assertThat(calc.scoreArea(ctx, TestFixtures.house(RentalType.PURCHASE, "11440", null, "18", null, null)).score()).isEqualTo(0.75);
            assertThat(calc.scoreArea(ctx, TestFixtures.house(RentalType.PURCHASE, "11440", null, "55", null, null)).score()).isEqualTo(0.5);
            assertThat(calc.scoreArea(ctx, TestFixtures.house(RentalType.PURCHASE, "11440", null, "85", null, null)).score()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("유형 = 주택 유형 일치와 공공임대 유형 일치의 평균 (희망: 아파트·다세대 / 매입임대·행복주택)")
        void type() {
            UserContext ctx = TestFixtures.context();
            assertThat(calc.scoreType(ctx, TestFixtures.house(RentalType.PURCHASE, "11440", HousingType.APARTMENT, null, null, null)).score()).isEqualTo(1.0);
            assertThat(calc.scoreType(ctx, TestFixtures.house(RentalType.PURCHASE, "11440", HousingType.OFFICETEL, null, null, null)).score()).isEqualTo(0.5);
            assertThat(calc.scoreType(ctx, TestFixtures.house(RentalType.NATIONAL, "11440", HousingType.APARTMENT, null, null, null)).score()).isEqualTo(0.5);
            assertThat(calc.scoreType(ctx, TestFixtures.house(RentalType.NATIONAL, "11440", HousingType.OFFICETEL, null, null, null)).score()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("주택 유형 정보가 없으면 공공임대 유형만으로 계산")
        void typeWithoutHousingType() {
            UserContext ctx = TestFixtures.context();
            ItemScore s = calc.scoreType(ctx, TestFixtures.house(RentalType.HAPPY, "11440", null, null, null, null));
            assertThat(s.available()).isTrue();
            assertThat(s.score()).isEqualTo(1.0);
            assertThat(s.reason().text()).contains("행복주택");
        }
    }

    @Nested
    @DisplayName("인프라 점수")
    class Infra {

        @Test
        @DisplayName("500m 이내 1점, 1500m 이상 0점, 사이는 비례")
        void distance() {
            assertThat(calc.distanceScore(300)).isEqualTo(1.0);
            assertThat(calc.distanceScore(1000)).isCloseTo(0.5, offset(0.001));
            assertThat(calc.distanceScore(2000)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("사용자 인프라 가중치로 가중 평균한다")
        void weightedByUser() {
            RecommendationProfile rp = RecommendationProfile.builder().userId(1L).name("역세권").active(true)
                    .weights("{}").rules("{}").infraWeights("{\"SW8\":5,\"MT1\":1}").build();
            UserContext ctx = UserContext.of(TestFixtures.youngSingle(), TestFixtures.mapoPreference(), rp,
                    props.baseWeights());
            SupplyHouse h = TestFixtures.goodHouse();
            // 역 300m(1.0, w5), 마트 없음(0, w1) → 5/6 ≈ 0.833
            var infra = TestFixtures.infra(h, "{\"SW8\":{\"name\":\"역\",\"distanceM\":300}}");
            assertThat(calc.scoreInfra(ctx, infra).score()).isCloseTo(0.8333, offset(0.001));
        }

        @Test
        void unavailableWithoutData() {
            assertThat(calc.scoreInfra(TestFixtures.context(), null).available()).isFalse();
        }
    }

    @Nested
    @DisplayName("가중합과 최종 점수")
    class Total {

        @Test
        @DisplayName("모든 항목 만점이면 100점")
        void perfect() {
            SupplyHouse h = TestFixtures.goodHouse();
            var infra = TestFixtures.infra(h, "{\"SW8\":{\"distanceM\":300},\"CS2\":{\"distanceM\":100}}");
            HouseScore s = calc.score(TestFixtures.context(), h, infra);
            assertThat(s.score()).isEqualTo(100);
            assertThat(s.excluded()).isFalse();
        }

        @Test
        @DisplayName("계산 못 한 항목은 빼고 남은 가중치를 다시 나눈다")
        void renormalize() {
            SupplyHouse h = TestFixtures.goodHouse();
            HouseScore s = calc.score(TestFixtures.context(), h, null); // 인프라 없음
            assertThat(s.itemScores().get("infra").available()).isFalse();
            assertThat(s.weights()).doesNotContainKey("infra");
            double sum = s.weights().values().stream().mapToDouble(Double::doubleValue).sum();
            assertThat(sum).isCloseTo(1.0, offset(0.001));
            assertThat(s.score()).isEqualTo(100);
        }

        @Test
        @DisplayName("사용자 우선순위가 가중치에 반영된다")
        void priorities() {
            RecommendationProfile rp = RecommendationProfile.builder().userId(1L).name("비용").active(true)
                    .weights("{\"cost\":5,\"region\":1,\"area\":1,\"infra\":1,\"type\":1}").rules("{}")
                    .infraWeights("{}").build();
            UserContext ctx = UserContext.of(TestFixtures.youngSingle(), TestFixtures.mapoPreference(), rp,
                    props.baseWeights());
            HouseScore s = calc.score(ctx, TestFixtures.goodHouse(), null);
            // cost 35×5=175, region 25, area 15, type 10 → 175/225 ≈ 0.778
            assertThat(s.weights().get("cost")).isCloseTo(0.7778, offset(0.001));
        }

        @Test
        @DisplayName("지원조건이 확인 필요면 총점에 0.7을 곱한다")
        void checkMultiplier() {
            var p = com.zipick.user.domain.UserProfile.builder().userId(1L)
                    .birthDate(java.time.LocalDate.now().minusYears(27)).married(false).householdSize(1)
                    .monthlyIncome(null).totalAssets(2500).carValue(0).homeless(true).build();
            UserContext ctx = UserContext.of(p, TestFixtures.mapoPreference(), null, props.baseWeights());
            HouseScore s = calc.score(ctx, TestFixtures.goodHouse(), null);
            assertThat(s.score()).isEqualTo(70);
            assertThat(s.eligibility().status()).isEqualTo(EligibilityResult.Status.CHECK);
        }

        @Test
        @DisplayName("MUST 항목이 0점이면 제외한다")
        void mustExcludes() {
            RecommendationProfile rp = RecommendationProfile.builder().userId(1L).name("지역 꼭").active(true)
                    .weights("{}").rules("{\"region\":\"MUST\"}").infraWeights("{}").build();
            UserContext ctx = UserContext.of(TestFixtures.youngSingle(), TestFixtures.mapoPreference(), rp,
                    props.baseWeights());
            SupplyHouse outside = TestFixtures.house(RentalType.PURCHASE, "11500", HousingType.APARTMENT, "29", 1500, 32);
            HouseScore s = calc.score(ctx, outside, null);
            assertThat(s.excluded()).isTrue();
            assertThat(s.excludedBy()).isEqualTo("region");
        }

        @Test
        @DisplayName("가격·면적 정보가 없으면 커버리지 부족으로 제외한다")
        void coverageExcludes() {
            // cost(35)+area(15) 없음 → 커버리지 50% < 60%
            SupplyHouse h = TestFixtures.house(RentalType.PURCHASE, "11440", HousingType.APARTMENT, null, null, null);
            HouseScore s = calc.score(TestFixtures.context(), h, null);
            assertThat(s.excluded()).isTrue();
            assertThat(s.excludedBy()).isEqualTo("coverage");
            assertThat(s.reasons()).anyMatch(r -> r.item().equals("coverage"));
        }

        @Test
        @DisplayName("인프라 정보만 없으면 커버리지 85%라 제외하지 않는다")
        void coverageOkWithoutInfra() {
            HouseScore s = calc.score(TestFixtures.context(), TestFixtures.goodHouse(), null);
            assertThat(s.excluded()).isFalse();
            assertThat(calc.coverage(s.itemScores())).isEqualTo(0.85);
        }

        @Test
        @DisplayName("근거에는 지원조건과 항목별 결과가 모두 들어간다")
        void reasons() {
            HouseScore s = calc.score(TestFixtures.context(),
                    TestFixtures.house(RentalType.PURCHASE, "11410", HousingType.MULTI_HOUSEHOLD, "18", 1500, 32), null);
            assertThat(s.reasons()).anyMatch(r -> r.item().equals("ELIGIBILITY") && r.mark() == Mark.PASS);
            assertThat(s.reasons()).anyMatch(r -> r.item().equals("region") && r.mark() == Mark.PARTIAL);
            assertThat(s.reasons()).anyMatch(r -> r.item().equals("area") && r.text().contains("18"));
        }
    }
}
