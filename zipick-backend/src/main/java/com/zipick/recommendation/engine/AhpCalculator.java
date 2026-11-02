package com.zipick.recommendation.engine;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * AHP(계층분석법) 쌍대 비교로 항목 가중치를 구한다.
 *
 * <pre>
 * 답(-2~2)  →  Saaty 척도   2: 5배, 1: 3배, 0: 같음, -1: 1/3, -2: 1/5
 * 비교 행렬 A[i][j] = i가 j보다 몇 배 중요한가, A[j][i] = 1 / A[i][j]
 * 가중치    = 행 기하평균을 합 1로 정규화
 * 일관성    CI = (λmax − n) / (n − 1),  CR = CI / RI(n)
 * </pre>
 *
 * 엇갈림 판정은 CR 이 아니라 '방향'으로 한다. 척도가 5단계(최대 5배)라
 * "A 훨씬 > B, B 훨씬 > C, A 훨씬 > C" 같은 자연스러운 답도 A:C 가 25배여야 해서 CR 이 0.25가 된다.
 * 그래서 A > B, B > C 인데 A ≤ C 처럼 순서가 뒤집힌 경우만 엇갈림으로 보고, CR 은 참고값으로 돌려준다.
 *
 * 비교한 항목끼리는 모든 쌍에 답이 있어야 한다 (항목 3개면 질문 3개).
 */
@Component
public class AhpCalculator {

    /** Saaty 권장 CR 기준. 참고용 */
    public static final double CONSISTENCY_LIMIT = 0.1;
    public static final int MAX_ANSWER = 2;

    /** Saaty 무작위 지수. n=1,2는 항상 일관적이라 0 */
    private static final double[] RANDOM_INDEX = {0, 0, 0, 0.58, 0.90, 1.12};

    /** left 가 right 보다 중요하면 양수 (1: 더, 2: 훨씬) */
    public record Comparison(String left, String right, int value) {}

    /**
     * @param weights          비교한 항목만, 합 1
     * @param consistencyRatio 0이면 완전히 일관. 참고값
     * @param consistent       답의 방향(순서)이 서로 맞으면 true
     */
    public record Result(Map<String, Double> weights, double consistencyRatio, boolean consistent) {}

    public Result calculate(List<Comparison> comparisons) {
        if (comparisons == null || comparisons.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "비교 답이 없습니다.");
        }

        // 항목 순서는 ScoreCalculator.ITEMS 기준으로 고정한다
        Set<String> involved = new HashSet<>();
        Set<String> pairs = new HashSet<>();
        for (Comparison c : comparisons) {
            validate(c);
            involved.add(c.left());
            involved.add(c.right());
            if (!pairs.add(pairKey(c.left(), c.right()))) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "같은 쌍을 두 번 비교했습니다: " + c.left() + "-" + c.right());
            }
        }
        List<String> items = new ArrayList<>();
        for (String item : ScoreCalculator.ITEMS) if (involved.contains(item)) items.add(item);
        int n = items.size();
        if (comparisons.size() != n * (n - 1) / 2) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "비교한 항목 " + n + "개의 모든 쌍(" + n * (n - 1) / 2 + "개)에 답해야 합니다.");
        }

        double[][] a = new double[n][n];
        for (int i = 0; i < n; i++) a[i][i] = 1;
        for (Comparison c : comparisons) {
            int i = items.indexOf(c.left());
            int j = items.indexOf(c.right());
            double v = saaty(c.value());
            a[i][j] = v;
            a[j][i] = 1 / v;
        }

        // 행 기하평균 → 정규화
        double[] w = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double logSum = 0;
            for (int j = 0; j < n; j++) logSum += Math.log(a[i][j]);
            w[i] = Math.exp(logSum / n);
            sum += w[i];
        }
        for (int i = 0; i < n; i++) w[i] /= sum;

        // λmax = 평균((A·w)_i / w_i)
        double lambda = 0;
        for (int i = 0; i < n; i++) {
            double aw = 0;
            for (int j = 0; j < n; j++) aw += a[i][j] * w[j];
            lambda += aw / w[i];
        }
        lambda /= n;
        double cr = n < 3 ? 0 : ((lambda - n) / (n - 1)) / RANDOM_INDEX[n];

        Map<String, Double> weights = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) weights.put(items.get(i), round4(w[i]));
        return new Result(weights, Math.max(0, round4(cr)), transitive(a));
    }

    /** i ≥ j, j ≥ k 이면 i ≥ k 이고, 둘 중 하나라도 '더'면 i > k 여야 한다. */
    static boolean transitive(double[][] a) {
        int n = a.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                for (int k = 0; k < n; k++) {
                    if (i == j || j == k || i == k) continue;
                    int ij = sign(a[i][j]), jk = sign(a[j][k]), ik = sign(a[i][k]);
                    if (ij >= 0 && jk >= 0 && ik < 0) return false;
                    if (ij > 0 && jk > 0 && ik <= 0) return false;
                }
            }
        }
        return true;
    }

    private static int sign(double ratio) {
        return ratio > 1 + 1e-9 ? 1 : ratio < 1 - 1e-9 ? -1 : 0;
    }

    /** -2..2 → 1/5, 1/3, 1, 3, 5 */
    static double saaty(int value) {
        return value >= 0 ? 2 * value + 1 : 1.0 / (2 * -value + 1);
    }

    private static void validate(Comparison c) {
        if (!ScoreCalculator.ITEMS.contains(c.left()) || !ScoreCalculator.ITEMS.contains(c.right())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "알 수 없는 항목: " + c.left() + ", " + c.right());
        }
        if (c.left().equals(c.right())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "같은 항목끼리는 비교할 수 없습니다: " + c.left());
        }
        if (Math.abs(c.value()) > MAX_ANSWER) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "답은 -2~2 사이여야 합니다.");
        }
    }

    private static String pairKey(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
