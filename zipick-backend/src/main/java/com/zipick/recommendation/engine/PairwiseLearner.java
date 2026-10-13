package com.zipick.recommendation.engine;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 쌍대 선택 학습 (Bradley-Terry 모형 = 차이 벡터에 대한 로지스틱 회귀).
 *
 * <pre>
 * d          = 왼쪽 집 항목 점수 − 오른쪽 집 항목 점수   (둘 다 계산 가능한 항목만, 아니면 0)
 * P(왼쪽 선택) = σ(β · d)
 * 손실       = −Σ [t·log p + (1−t)·log(1−p)] + λ/2 · ‖β − β₀‖²
 *              t: 왼쪽 1, 오른쪽 0, 비슷해요 0.5
 * β₀         = SCALE × 기본 가중치 (사전 분포). 답이 적으면 기본값 근처, 쌓일수록 실제 선택 쪽으로 움직인다
 * 학습 가중치 = β 를 합 1로 정규화
 * </pre>
 *
 * 손실이 볼록이라 경사하강법으로 항상 같은 답에 수렴한다. β 는 음수가 되지 않게 아래를 막는다
 * (항목 점수가 높을수록 좋다는 전제는 유지).
 */
@Component
public class PairwiseLearner {

    /** 총점(0~1) 0.1 차이 ≈ 로짓 1 (선택 확률 73%) */
    static final double SCALE = 10;
    /**
     * 사전 분포 강도 λ. 클수록 기본 가중치에서 덜 움직인다.
     * 모의 사용자 실험(항목 5개, 무작위 쌍)에서 λ=1 이 10번 답으로 중시 항목을 우선순위 4 이상으로 찾는 비율 약 90%,
     * "비슷해요"만 답했을 때 흔들림 1칸 남짓으로 가장 균형이 좋았다.
     */
    static final double PRIOR_STRENGTH = 1.0;
    /** β 하한 = SCALE × 이 값 */
    static final double MIN_WEIGHT = 0.02;
    static final int ITERATIONS = 1000;
    /** 이미 나온 집이 다시 뽑힐 때 정보량에 곱하는 값 (한 번 나올 때마다) */
    static final double REPEAT_PENALTY = 0.5;
    static final double MIN_INFO = 1e-4;

    /** @param target 왼쪽 선택 1, 오른쪽 선택 0, 비슷해요 0.5 */
    public record Observation(Map<String, Double> diff, double target) {}

    /**
     * @param weights   학습된 가중치 (합 1)
     * @param beta      학습된 계수. 다음 질문 고를 때 쓴다
     * @param agreement "비슷해요"를 뺀 답 중 학습 결과와 맞는 비율. 답이 없으면 null
     */
    public record Result(Map<String, Double> weights, Map<String, Double> beta, Double agreement, int observations) {}

    public Result learn(List<Observation> observations, Map<String, Double> priorWeights) {
        int k = ScoreCalculator.ITEMS.size();
        double[] beta0 = new double[k];
        double[] beta = new double[k];
        for (int i = 0; i < k; i++) {
            beta0[i] = SCALE * priorWeights.getOrDefault(ScoreCalculator.ITEMS.get(i), 0.0);
            beta[i] = beta0[i];
        }
        double[][] x = new double[observations.size()][];
        double curvature = PRIOR_STRENGTH;
        for (int n = 0; n < observations.size(); n++) {
            x[n] = toVector(observations.get(n).diff());
            curvature += 0.25 * dot(x[n], x[n]);
        }
        double step = 1.0 / curvature;    // 헤시안 상한의 역수 → 발산하지 않는 보폭
        double floor = SCALE * MIN_WEIGHT;

        for (int it = 0; it < ITERATIONS; it++) {
            double[] grad = new double[k];
            for (int i = 0; i < k; i++) grad[i] = PRIOR_STRENGTH * (beta[i] - beta0[i]);
            for (int n = 0; n < x.length; n++) {
                double p = sigmoid(dot(beta, x[n]));
                double err = observations.get(n).target() - p;
                for (int i = 0; i < k; i++) grad[i] -= err * x[n][i];
            }
            for (int i = 0; i < k; i++) beta[i] = Math.max(floor, beta[i] - step * grad[i]);
        }

        double sum = 0;
        for (double b : beta) sum += b;
        Map<String, Double> weights = new LinkedHashMap<>();
        Map<String, Double> betaMap = new LinkedHashMap<>();
        for (int i = 0; i < k; i++) {
            weights.put(ScoreCalculator.ITEMS.get(i), round4(beta[i] / sum));
            betaMap.put(ScoreCalculator.ITEMS.get(i), beta[i]);
        }

        int decided = 0, agreed = 0;
        for (int n = 0; n < x.length; n++) {
            double t = observations.get(n).target();
            if (t == 0.5) continue;
            decided++;
            if ((dot(beta, x[n]) > 0) == (t > 0.5)) agreed++;
        }
        Double agreement = decided == 0 ? null : round4((double) agreed / decided);
        return new Result(weights, betaMap, agreement, observations.size());
    }

    /** 두 집의 항목 점수 차이. 한쪽이라도 계산 못 한 항목은 비교에서 뺀다. */
    public static Map<String, Double> diff(HouseScore left, HouseScore right) {
        Map<String, Double> d = new LinkedHashMap<>();
        for (String item : ScoreCalculator.ITEMS) {
            ItemScore a = left.itemScores().get(item);
            ItemScore b = right.itemScores().get(item);
            if (a != null && b != null && a.available() && b.available()) d.put(item, a.score() - b.score());
        }
        return d;
    }

    /**
     * 다음에 물어볼 두 집. 점수 = p(1−p) × ‖d‖² × 0.5^(두 집이 이미 나온 횟수) 가 가장 큰 쌍을 고른다.
     * 모델이 어느 쪽을 고를지 헷갈리고(p≈0.5) 항목 차이가 뚜렷한 쌍일수록 답 하나로 많이 배운다.
     * 다만 정보량만 보면 특이한 집 하나가 계속 나와 지루하고, 그 집의 특징끼리 섞여 구분이 안 된다.
     * 그래서 이미 나온 집은 횟수만큼 덜 뽑는다.
     *
     * @param askedKeys 이미 물어본 쌍 (작은 id-큰 id)
     * @param shown     집별로 이미 보여 준 횟수
     */
    public Optional<HouseScore[]> nextPair(List<HouseScore> candidates, Set<String> askedKeys,
                                           Map<Long, Integer> shown, Map<String, Double> beta, int maxCandidates) {
        List<HouseScore> pool = candidates.stream()
                .filter(s -> !s.excluded())
                .sorted(Comparator.comparingInt(HouseScore::score).reversed()
                        .thenComparing(s -> s.house().getId()))
                .limit(maxCandidates)
                .toList();
        double[] b = toVector(beta);
        HouseScore[] best = null;
        double bestInfo = 0;
        for (int i = 0; i < pool.size(); i++) {
            for (int j = i + 1; j < pool.size(); j++) {
                HouseScore l = pool.get(i), r = pool.get(j);
                if (askedKeys.contains(pairKey(l.house().getId(), r.house().getId()))) continue;
                double[] d = toVector(diff(l, r));
                double p = sigmoid(dot(b, d));
                double raw = p * (1 - p) * dot(d, d);
                if (raw <= MIN_INFO) continue;   // 항목 차이가 거의 없는 쌍은 묻지 않는다
                int repeats = shown.getOrDefault(l.house().getId(), 0) + shown.getOrDefault(r.house().getId(), 0);
                double info = raw * Math.pow(REPEAT_PENALTY, repeats);
                if (info > bestInfo + 1e-12) {
                    bestInfo = info;
                    best = new HouseScore[]{l, r};
                }
            }
        }
        return Optional.ofNullable(best);
    }

    public static String pairKey(long a, long b) {
        return Math.min(a, b) + "-" + Math.max(a, b);
    }

    private static double[] toVector(Map<String, Double> m) {
        double[] v = new double[ScoreCalculator.ITEMS.size()];
        for (int i = 0; i < v.length; i++) v[i] = m.getOrDefault(ScoreCalculator.ITEMS.get(i), 0.0);
        return v;
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    private static double sigmoid(double z) {
        return 1 / (1 + Math.exp(-z));
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
