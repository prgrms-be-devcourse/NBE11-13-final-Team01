package com.zipick.recommendation.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.SupplyHouse;
import com.zipick.recommendation.engine.PairwiseLearner.Observation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PairwiseLearnerTest {

    private static final Map<String, Integer> BASE = TestFixtures.props().baseWeights();
    private static final Map<String, Double> PRIOR = PriorityConverter.toWeights(
            Map.of("cost", 3, "region", 3, "area", 3, "infra", 3, "type", 3), BASE);

    private final PairwiseLearner learner = new PairwiseLearner();

    /** 진짜 가중치가 trueWeights 인 사용자가 n번 고른 기록 (항상 점수가 높은 쪽을 고른다) */
    private List<Observation> simulate(Map<String, Double> trueWeights, int n, long seed) {
        Random rnd = new Random(seed);
        List<Observation> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Double> d = new LinkedHashMap<>();
            double u = 0;
            for (String item : ScoreCalculator.ITEMS) {
                double v = rnd.nextDouble() * 2 - 1;     // 항목 점수 차이 -1~1
                d.put(item, v);
                u += trueWeights.get(item) * v;
            }
            out.add(new Observation(d, u > 0 ? 1.0 : 0.0));
        }
        return out;
    }

    @Test
    @DisplayName("답이 없으면 사전 분포(기본 가중치) 그대로 → 우선순위 모두 3")
    void noAnswers() {
        var r = learner.learn(List.of(), PRIOR);

        PRIOR.forEach((k, v) -> assertThat(r.weights().get(k)).isCloseTo(v, within(1e-3)));
        assertThat(PriorityConverter.fromWeights(r.weights(), BASE)).containsOnlyKeys(ScoreCalculator.ITEMS)
                .allSatisfy((k, p) -> assertThat(p).isEqualTo(3));
        assertThat(r.agreement()).isNull();
    }

    @Test
    @DisplayName("인프라를 중시하는 사용자의 선택 10번 → 인프라 우선순위가 올라가고 비용은 내려간다")
    void learnsInfraLover() {
        Map<String, Double> truth = Map.of("cost", 0.1, "region", 0.15, "area", 0.1, "infra", 0.6, "type", 0.05);
        var r = learner.learn(simulate(truth, 10, 7), PRIOR);

        Map<String, Integer> p = PriorityConverter.fromWeights(r.weights(), BASE);
        assertThat(r.weights().get("infra")).isGreaterThan(PRIOR.get("infra"));
        assertThat(p.get("infra")).isGreaterThan(3);
        assertThat(p.get("infra")).isGreaterThan(p.get("cost"));
        assertThat(r.agreement()).isGreaterThanOrEqualTo(0.8);
    }

    @Test
    @DisplayName("답이 많을수록 진짜 가중치에 가까워진다")
    void moreAnswersCloser() {
        Map<String, Double> truth = Map.of("cost", 0.5, "region", 0.05, "area", 0.05, "infra", 0.3, "type", 0.1);
        double err5 = l1(learner.learn(simulate(truth, 5, 11), PRIOR).weights(), truth);
        double err40 = l1(learner.learn(simulate(truth, 40, 11), PRIOR).weights(), truth);

        assertThat(err40).isLessThan(err5);
    }

    @Test
    @DisplayName("전부 '비슷해요'면 우선순위가 기본값(3)에서 한 칸 넘게 움직이지 않는다")
    void tiesStayNearPrior() {
        List<Observation> ties = simulate(PRIOR, 8, 3).stream()
                .map(o -> new Observation(o.diff(), 0.5)).toList();
        var r = learner.learn(ties, PRIOR);

        assertThat(PriorityConverter.fromWeights(r.weights(), BASE).values()).allSatisfy(p -> assertThat(p).isBetween(2, 4));
        assertThat(r.agreement()).isNull();
    }

    @Test
    @DisplayName("가중치는 음수가 되지 않는다")
    void nonNegative() {
        Map<String, Double> truth = Map.of("cost", 1.0, "region", 0.0, "area", 0.0, "infra", 0.0, "type", 0.0);
        var r = learner.learn(simulate(truth, 30, 5), PRIOR);

        assertThat(r.weights().values()).allSatisfy(w -> assertThat(w).isPositive());
        assertThat(r.weights().values().stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(1.0, within(1e-3));
    }

    @Test
    @DisplayName("다음 질문은 이미 물어본 쌍을 빼고, 항목 차이가 크면서 결과가 헷갈리는 쌍")
    void nextPair() {
        // 1: 비용 좋고 인프라 나쁨, 2: 비용 나쁘고 인프라 좋음 → 서로 엇갈려 정보가 많다
        // 3: 1과 거의 같음 → 1-3 쌍은 정보가 없다
        HouseScore h1 = house(1, Map.of("cost", 1.0, "infra", 0.0));
        HouseScore h2 = house(2, Map.of("cost", 0.0, "infra", 1.0));
        HouseScore h3 = house(3, Map.of("cost", 0.95, "infra", 0.05));
        Map<String, Double> beta = new LinkedHashMap<>();
        PRIOR.forEach((k, v) -> beta.put(k, v * 10));

        var first = learner.nextPair(List.of(h1, h2, h3), Set.of(), Map.of(), beta, 40).orElseThrow();
        assertThat(Set.of(first[0].house().getId(), first[1].house().getId())).contains(2L);

        String key = PairwiseLearner.pairKey(first[0].house().getId(), first[1].house().getId());
        var second = learner.nextPair(List.of(h1, h2, h3), Set.of(key), Map.of(), beta, 40).orElseThrow();
        assertThat(PairwiseLearner.pairKey(second[0].house().getId(), second[1].house().getId())).isNotEqualTo(key);

        var none = learner.nextPair(List.of(h1, h2, h3), Set.of("1-2", "2-3", "1-3"), Map.of(), beta, 40);
        assertThat(none).isEmpty();
    }

    @Test
    @DisplayName("이미 여러 번 나온 집은 정보량이 조금 더 커도 덜 뽑힌다")
    void avoidsRepeats() {
        // 1-2 쌍이 정보량이 가장 크지만, 1번이 이미 3번 나왔다면 3-4 쌍을 고른다
        HouseScore h1 = house(1, Map.of("cost", 1.0, "infra", 0.0));
        HouseScore h2 = house(2, Map.of("cost", 0.0, "infra", 1.0));
        HouseScore h3 = house(3, Map.of("cost", 0.9, "infra", 0.1));
        HouseScore h4 = house(4, Map.of("cost", 0.1, "infra", 0.9));
        Map<String, Double> beta = new LinkedHashMap<>();
        PRIOR.forEach((k, v) -> beta.put(k, v * 10));

        var fresh = learner.nextPair(List.of(h1, h2, h3, h4), Set.of(), Map.of(), beta, 40).orElseThrow();
        assertThat(PairwiseLearner.pairKey(fresh[0].house().getId(), fresh[1].house().getId())).isEqualTo("1-2");

        var tired = learner.nextPair(List.of(h1, h2, h3, h4), Set.of(), Map.of(1L, 3), beta, 40).orElseThrow();
        assertThat(Set.of(tired[0].house().getId(), tired[1].house().getId())).doesNotContain(1L);
    }

    private static HouseScore house(long id, Map<String, Double> scores) {
        SupplyHouse h = SupplyHouse.builder().address("a").approximate(false).housingType(HousingType.APARTMENT).build();
        ReflectionTestUtils.setField(h, "id", id);
        Map<String, ItemScore> items = new LinkedHashMap<>();
        for (String item : ScoreCalculator.ITEMS) {
            Double s = scores.get(item);
            items.put(item, s == null ? ItemScore.of(item, 0.5, Reason.pass(item, "-")) : ItemScore.of(item, s, Reason.pass(item, "-")));
        }
        return new HouseScore(h, 80, items, Map.of(),
                new EligibilityResult(EligibilityResult.Status.PASS, List.of()), List.of(), null);
    }

    private static double l1(Map<String, Double> a, Map<String, Double> b) {
        double s = 0;
        for (String item : ScoreCalculator.ITEMS) s += Math.abs(a.get(item) - b.get(item));
        return s;
    }
}
