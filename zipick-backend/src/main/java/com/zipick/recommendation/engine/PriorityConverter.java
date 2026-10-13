package com.zipick.recommendation.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 가중치 ↔ 우선순위(1~5) 변환.
 *
 * <pre>
 * 최종 가중치 = 기본 가중치 × 우선순위 를 합 1로 정규화 (ScoreCalculator 와 같은 식)
 * 우선순위    = 3 × (비율 ÷ 비율들의 기하평균) 을 반올림, 1~5로 자른다
 * </pre>
 * 기하평균을 3에 맞추므로 모든 비율이 같으면 전부 3(기본값)이 된다.
 */
public final class PriorityConverter {

    public static final int NEUTRAL = 3;
    public static final int MIN = 1;
    public static final int MAX = 5;

    private PriorityConverter() {}

    /** 항목별 상대 비율을 우선순위로 바꾼다. 비율이 없는 항목은 결과에 넣지 않는다. */
    public static Map<String, Integer> fromRatios(Map<String, Double> ratios) {
        double logSum = 0;
        int n = 0;
        for (Double r : ratios.values()) {
            if (r != null && r > 0) {
                logSum += Math.log(r);
                n++;
            }
        }
        double gm = n == 0 ? 1 : Math.exp(logSum / n);
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String item : ScoreCalculator.ITEMS) {
            Double r = ratios.get(item);
            if (r == null) continue;
            out.put(item, r <= 0 ? MIN : clamp((int) Math.round(NEUTRAL * r / gm)));
        }
        return out;
    }

    /** 정규화 가중치(합 1)를 우선순위로. 기본 가중치로 나눈 값이 우선순위 비율이다. */
    public static Map<String, Integer> fromWeights(Map<String, Double> weights, Map<String, Integer> base) {
        Map<String, Double> ratios = new LinkedHashMap<>();
        for (String item : ScoreCalculator.ITEMS) {
            Double w = weights.get(item);
            int b = base.getOrDefault(item, 0);
            if (w != null && b > 0) ratios.put(item, w / b);
        }
        return fromRatios(ratios);
    }

    /** 우선순위를 정규화 가중치(합 1)로. 모든 항목이 계산 가능하다고 본다. */
    public static Map<String, Double> toWeights(Map<String, Integer> priorities, Map<String, Integer> base) {
        Map<String, Double> raw = new LinkedHashMap<>();
        double sum = 0;
        for (String item : ScoreCalculator.ITEMS) {
            double w = base.getOrDefault(item, 0) * clamp(priorities.getOrDefault(item, NEUTRAL));
            raw.put(item, w);
            sum += w;
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (var e : raw.entrySet()) out.put(e.getKey(), sum == 0 ? 0 : e.getValue() / sum);
        return out;
    }

    static int clamp(int p) {
        return Math.max(MIN, Math.min(MAX, p));
    }
}
