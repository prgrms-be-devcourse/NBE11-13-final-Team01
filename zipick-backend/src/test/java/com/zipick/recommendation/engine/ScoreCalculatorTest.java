package com.zipick.recommendation.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import com.zipick.housing.domain.Candidate;
import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import com.zipick.recommendation.config.RecommendationProperties;
import com.zipick.recommendation.domain.RecommendationProfile;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
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
        @DisplayName("월 환산 이율 = min(10%, 기준금리 3% + 2%p) = 5%")
        void conversionRate() {
            assertThat(props.conversionRate()).isEqualTo(5.0);
        }

        @Test
        @DisplayName("월 환산 비용 = 월세 + 보증금 × 이율 ÷ 12")
        void monthlyCost() {
            // 32 + 1500 × 0.05 / 12 = 32 + 6.25 = 38.25
            assertThat(calc.monthlyCost(TestFixtures.goodHouse())).isCloseTo(38.25, offset(0.001));
        }

        @Test
        @DisplayName("월세 정보가 없으면 보증금만 환산하고 근거에 적는다 (전세형)")
        void depositOnly() {
            Candidate c = TestFixtures.house(RentalType.DEUNDEUN, "마포구", HousingType.APARTMENT, "29", 9000, null);
            ItemScore s = calc.scoreCost(TestFixtures.context(), c);
            assertThat(s.available()).isTrue();
            assertThat(calc.monthlyCost(c)).isCloseTo(37.5, offset(0.001)); // 9000 × 5% / 12
            assertThat(s.reason().text()).contains("보증금만 환산");
        }

        @Test
        @DisplayName("예산을 15% 넘으면 0.5점, 30% 넘으면 0점")
        void overBudget() {
            // 예산 45 × 1.15 = 51.75 → 보증금 1500(6.25) + 월세 45.5
            double s15 = calc.scoreCost(TestFixtures.context(),
                    TestFixtures.house(RentalType.YOUTH_PURCHASE, "마포구", HousingType.MULTI_HOUSEHOLD, "29", 1500, 45)).score();
            assertThat(s15).isCloseTo(0.5, offset(0.05));
            assertThat(calc.scoreCost(TestFixtures.context(),
                    TestFixtures.house(RentalType.YOUTH_PURCHASE, "마포구", HousingType.MULTI_HOUSEHOLD, "29", 1500, 60)).score())
                    .isEqualTo(0.0);
        }

        @Test
        @DisplayName("더 싼 집의 비용 점수가 더 낮을 수 없다 (단조성)")
        void monotonic() {
            UserContext ctx = TestFixtures.context();
            double prev = 2;
            for (int rent = 20; rent <= 70; rent += 5) {
                double s = calc.scoreCost(ctx,
                        TestFixtures.house(RentalType.YOUTH_PURCHASE, "마포구", HousingType.MULTI_HOUSEHOLD, "29", 1500, rent)).score();
                assertThat(s).isLessThanOrEqualTo(prev);
                prev = s;
            }
        }

        @Test
        @DisplayName("보증금·월세가 모두 없으면 계산 제외")
        void unavailable() {
            Candidate c = TestFixtures.house(RentalType.YOUTH_PURCHASE, "마포구", HousingType.MULTI_HOUSEHOLD, "29", null, null);
            assertThat(calc.scoreCost(TestFixtures.context(), c).available()).isFalse();
        }
    }

    @Nested
    @DisplayName("지역·면적·방 수·유형·노후도")
    class OtherItems {

        @Test
        @DisplayName("지역은 '시도 시군구'로 비교하고 관심 순위별 점수")
        void regionByRank() {
            UserContext ctx = TestFixtures.context();
            assertThat(calc.scoreRegion(ctx, TestFixtures.house(null, "마포구", null, null, null, null)).score()).isEqualTo(1.0);
            assertThat(calc.scoreRegion(ctx, TestFixtures.house(null, "서대문구", null, null, null, null)).score()).isEqualTo(0.8);
            assertThat(calc.scoreRegion(ctx, TestFixtures.house(null, "은평구", null, null, null, null)).score()).isEqualTo(0.6);
            assertThat(calc.scoreRegion(ctx, TestFixtures.house(null, "강서구", null, null, null, null)).score()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("면적은 범위 안 1점, 10㎡ 벗어날 때마다 0.25 감점")
        void area() {
            UserContext ctx = TestFixtures.context(); // 20~40
            assertThat(calc.scoreArea(ctx, TestFixtures.house(null, "마포구", null, "29.4", null, null)).score()).isEqualTo(1.0);
            assertThat(calc.scoreArea(ctx, TestFixtures.house(null, "마포구", null, "18", null, null)).score()).isEqualTo(0.75);
            assertThat(calc.scoreArea(ctx, TestFixtures.house(null, "마포구", null, "55", null, null)).score()).isEqualTo(0.5);
            assertThat(calc.scoreArea(ctx, TestFixtures.house(null, "마포구", null, "85", null, null)).score()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("세대원 수에 맞는 최저주거기준보다 작으면 근거에 표시한다")
        void minimumHousingStandard() {
            UserContext ctx = UserContext.of(TestFixtures.profile(30, 3, 300, 5000), TestFixtures.mapoPreference(), null,
                    props.baseWeights());
            ItemScore s = calc.scoreArea(ctx, TestFixtures.house(null, "마포구", null, "30", null, null)); // 3인 36㎡
            assertThat(s.score()).isEqualTo(1.0);
            assertThat(s.reason().text()).contains("최저주거기준(3인 36㎡) 미달");
            assertThat(s.reason().mark()).isEqualTo(Mark.PARTIAL);
        }

        @Test
        @DisplayName("방 수: 희망 이상 1점, 1개 모자랄 때마다 0.5 감점, 희망이 없으면 제외")
        void rooms() {
            UserContext ctx = UserContext.of(TestFixtures.youngSingle(),
                    com.zipick.user.domain.UserPreference.builder().userId(1L).regions("[]").roomsMin(3).build(),
                    null, props.baseWeights());
            assertThat(calc.scoreRooms(ctx, TestFixtures.candidate(1, 1, null, "마포구", null, null, null, null, 3, null)).score()).isEqualTo(1.0);
            assertThat(calc.scoreRooms(ctx, TestFixtures.candidate(1, 1, null, "마포구", null, null, null, null, 2, null)).score()).isEqualTo(0.5);
            assertThat(calc.scoreRooms(ctx, TestFixtures.candidate(1, 1, null, "마포구", null, null, null, null, 1, null)).score()).isEqualTo(0.0);
            assertThat(calc.scoreRooms(ctx, TestFixtures.candidate(1, 1, null, "마포구", null, null, null, null, null, null)).available()).isFalse();
        }

        @Test
        @DisplayName("주택 유형은 희망 유형과 일치하면 1점")
        void type() {
            UserContext ctx = TestFixtures.context(); // 아파트·다세대
            assertThat(calc.scoreType(ctx, TestFixtures.house(null, "마포구", HousingType.APARTMENT, null, null, null)).score()).isEqualTo(1.0);
            assertThat(calc.scoreType(ctx, TestFixtures.house(null, "마포구", HousingType.OFFICETEL, null, null, null)).score()).isEqualTo(0.0);
            assertThat(calc.scoreType(ctx, TestFixtures.house(null, "마포구", null, null, null, null)).available()).isFalse();
        }

        @Test
        @DisplayName("노후도: 신축 1점, 30년이면 0점, 준공연도가 없으면 제외")
        void age() {
            int year = LocalDate.now().getYear();
            UserContext ctx = TestFixtures.context();
            assertThat(calc.scoreAge(ctx, TestFixtures.candidate(1, 1, null, "마포구", null, null, null, null, 1, year)).score()).isEqualTo(1.0);
            assertThat(calc.scoreAge(ctx, TestFixtures.candidate(1, 1, null, "마포구", null, null, null, null, 1, year - 15)).score()).isEqualTo(0.5);
            assertThat(calc.scoreAge(ctx, TestFixtures.candidate(1, 1, null, "마포구", null, null, null, null, 1, year - 40)).score()).isEqualTo(0.0);
            assertThat(calc.scoreAge(ctx, TestFixtures.goodHouse()).available()).isFalse();
        }
    }

    @Nested
    @DisplayName("인프라 점수")
    class Infra {

        @Test
        @DisplayName("350m 이내 1점, 1500m 이상 0점, 사이는 비례")
        void distance() {
            assertThat(calc.distanceScore(300)).isEqualTo(1.0);
            assertThat(calc.distanceScore(925)).isCloseTo(0.5, offset(0.001));
            assertThat(calc.distanceScore(2000)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("사용자 인프라 가중치로 가중 평균한다")
        void weightedByUser() {
            RecommendationProfile rp = RecommendationProfile.builder().userId(1L).name("역세권").active(true)
                    .weights("{}").rules("{}").infraWeights("{\"SW8\":5,\"MT1\":1}").build();
            UserContext ctx = UserContext.of(TestFixtures.youngSingle(), TestFixtures.mapoPreference(), rp, props.baseWeights());
            // 역 300m(1.0, w5), 마트 없음(0, w1) → 5/6 ≈ 0.833
            assertThat(calc.scoreInfra(ctx, Map.of("SW8", 300)).score()).isCloseTo(0.8333, offset(0.001));
        }

        @Test
        @DisplayName("주변 시설 정보가 없으면 계산 제외 (좌표가 생기기 전 기본 상태)")
        void unavailableWithoutData() {
            assertThat(calc.scoreInfra(TestFixtures.context(), null).available()).isFalse();
            assertThat(calc.scoreInfra(TestFixtures.context(), Map.of()).available()).isFalse();
        }

        /**
         * 주변 인프라 담당(InfraDistanceProvider)은 수집한 주택이면 카테고리 key 를 모두 담고,
         * 반경 안에 시설이 없던 카테고리는 값을 null 로 둔다.
         * 아래 세 테스트가 그 규약에 기대는 동작이다.
         */
        @Test
        @DisplayName("가중치가 없으면 수집 대상 카테고리 전체를 같은 비중으로 본다")
        void defaultWeightCoversEveryCollectedCategory() {
            Map<String, Integer> nearest = new HashMap<>();
            nearest.put("SW8", 300);   // 1.0
            nearest.put("MT1", null);  // 반경 내 없음 → 0
            nearest.put("CS2", null);  // 반경 내 없음 → 0

            // 1.0 + 0 + 0 을 3으로 나눈다. (예전에는 찾은 카테고리만 봐서 1.0 이었다)
            assertThat(calc.scoreInfra(TestFixtures.context(), nearest).score())
                    .isCloseTo(0.3333, offset(0.001));
        }

        @Test
        @DisplayName("정상 수집했는데 주변이 전부 0건이면 '정보 없음'이 아니라 0점이다")
        void zeroScoreWhenNothingNearby() {
            Map<String, Integer> nearest = new HashMap<>();
            nearest.put("SW8", null);
            nearest.put("MT1", null);

            ItemScore score = calc.scoreInfra(TestFixtures.context(), nearest);

            assertThat(score.available()).isTrue();
            assertThat(score.score()).isZero();
            assertThat(score.reason().text()).contains("지하철역 없음").contains("대형마트 없음");
        }

        @Test
        @DisplayName("사용자 가중치가 있으면 기존대로 지정한 카테고리만 본다")
        void userWeightsStillTakePrecedence() {
            RecommendationProfile rp = RecommendationProfile.builder().userId(1L).name("역세권").active(true)
                    .weights("{}").rules("{}").infraWeights("{\"SW8\":5,\"MT1\":1}").build();
            UserContext ctx = UserContext.of(TestFixtures.youngSingle(), TestFixtures.mapoPreference(), rp,
                    props.baseWeights());

            Map<String, Integer> nearest = new HashMap<>();
            nearest.put("SW8", 300);
            nearest.put("MT1", null);
            nearest.put("CS2", 100);   // 가중치에 없으므로 계산에 들어가지 않는다

            assertThat(calc.scoreInfra(ctx, nearest).score()).isCloseTo(0.8333, offset(0.001));
        }
    }

    @Nested
    @DisplayName("가중합과 최종 점수")
    class Total {

        @Test
        @DisplayName("모든 항목 만점이면 100점")
        void perfect() {
            int year = LocalDate.now().getYear();
            Candidate c = TestFixtures.candidate(1, 1, RentalType.YOUTH_PURCHASE, "마포구", HousingType.MULTI_HOUSEHOLD,
                    "29.4", 1500, 32, 1, year);
            HouseScore s = calc.score(TestFixtures.context(), c, Map.of("SW8", 300, "CS2", 100));
            assertThat(s.score()).isEqualTo(100);
            assertThat(s.complete()).isTrue();
            assertThat(s.excluded()).isFalse();
        }

        @Test
        @DisplayName("계산 못 한 항목(인프라·노후도)은 빼고 남은 가중치를 다시 나눈다")
        void renormalize() {
            HouseScore s = calc.score(TestFixtures.context(), TestFixtures.goodHouse(), null);
            assertThat(s.weights()).doesNotContainKeys("infra", "age");
            double sum = s.weights().values().stream().mapToDouble(Double::doubleValue).sum();
            assertThat(sum).isCloseTo(1.0, offset(0.001));
            assertThat(s.score()).isEqualTo(100);
        }

        @Test
        @DisplayName("사용자 우선순위가 가중치에 반영된다")
        void priorities() {
            RecommendationProfile rp = RecommendationProfile.builder().userId(1L).name("비용").active(true)
                    .weights("{\"cost\":5,\"region\":1,\"area\":1,\"rooms\":1,\"infra\":1,\"age\":1,\"type\":1}")
                    .rules("{}").infraWeights("{}").build();
            UserContext ctx = UserContext.of(TestFixtures.youngSingle(), TestFixtures.mapoPreference(), rp, props.baseWeights());
            HouseScore s = calc.score(ctx, TestFixtures.goodHouse(), null);
            // cost 30×5=150, region 25, area 15, rooms 10, type 5 → 150/205 ≈ 0.7317
            assertThat(s.weights().get("cost")).isCloseTo(0.7317, offset(0.001));
        }

        @Test
        @DisplayName("지원조건이 확인 필요면 총점에 0.7을 곱한다")
        void checkMultiplier() {
            UserContext ctx = UserContext.of(TestFixtures.profile(27, 1, null, 2500), TestFixtures.mapoPreference(),
                    null, props.baseWeights());
            HouseScore s = calc.score(ctx, TestFixtures.goodHouse(), null);
            assertThat(s.score()).isEqualTo(70);
            assertThat(s.eligibility().status()).isEqualTo(EligibilityResult.Status.CHECK);
        }

        @Test
        @DisplayName("MUST 항목이 0점이면 제외한다")
        void mustExcludes() {
            RecommendationProfile rp = RecommendationProfile.builder().userId(1L).name("지역 꼭").active(true)
                    .weights("{}").rules("{\"region\":\"MUST\"}").infraWeights("{}").build();
            UserContext ctx = UserContext.of(TestFixtures.youngSingle(), TestFixtures.mapoPreference(), rp, props.baseWeights());
            HouseScore s = calc.score(ctx,
                    TestFixtures.house(RentalType.YOUTH_PURCHASE, "강서구", HousingType.APARTMENT, "29", 1500, 32), null);
            assertThat(s.excluded()).isTrue();
            assertThat(s.excludedBy()).isEqualTo("region");
        }

        @Test
        @DisplayName("관심 지역은 기본 '꼭' 조건: 규칙을 안 정하면 관심 지역 밖은 제외, '되면 좋음'으로 바꾸면 포함")
        void regionMustByDefault() {
            var outside = TestFixtures.house(RentalType.YOUTH_PURCHASE, "강서구", HousingType.APARTMENT, "29", 1500, 32);
            UserContext byDefault = UserContext.of(TestFixtures.youngSingle(), TestFixtures.mapoPreference(), null,
                    props.baseWeights(), java.util.List.of("region"));
            assertThat(calc.score(byDefault, outside, null).excludedBy()).isEqualTo("region");

            RecommendationProfile prefer = RecommendationProfile.builder().userId(1L).name("넓게").active(true)
                    .weights("{}").rules("{\"region\":\"PREFER\"}").infraWeights("{}").build();
            UserContext relaxed = UserContext.of(TestFixtures.youngSingle(), TestFixtures.mapoPreference(), prefer,
                    props.baseWeights(), java.util.List.of("region"));
            assertThat(calc.score(relaxed, outside, null).excluded()).isFalse();
        }

        @Test
        @DisplayName("비용 정보가 없으면 제외하지 않고 '정보 부족' 묶음으로 보낸다 (설계 결정 D안)")
        void incompleteWithoutCost() {
            Candidate c = TestFixtures.house(RentalType.YOUTH_PURCHASE, "마포구", HousingType.APARTMENT, "29", null, null);
            HouseScore s = calc.score(TestFixtures.context(), c, null);
            assertThat(s.excluded()).isFalse();
            assertThat(s.complete()).isFalse();
            assertThat(s.reasons()).anyMatch(r -> r.item().equals("required") && r.text().contains("비용"));
        }

        @Test
        @DisplayName("근거에는 지원조건과 항목별 결과가 모두 들어간다")
        void reasons() {
            HouseScore s = calc.score(TestFixtures.context(),
                    TestFixtures.house(RentalType.YOUTH_PURCHASE, "서대문구", HousingType.MULTI_HOUSEHOLD, "18", 1500, 32), null);
            assertThat(s.reasons()).anyMatch(r -> r.item().equals("ELIGIBILITY") && r.mark() == Mark.PASS);
            assertThat(s.reasons()).anyMatch(r -> r.item().equals("region") && r.mark() == Mark.PARTIAL);
            assertThat(s.reasons()).anyMatch(r -> r.item().equals("area") && r.text().contains("18"));
        }
    }
}
