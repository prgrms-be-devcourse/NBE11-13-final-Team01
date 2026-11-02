package com.zipick.recommendation.engine;

/**
 * 항목 하나의 점수. score 는 0~1, 값이 없어 계산하지 못했으면 available=false.
 * 계산하지 못한 항목은 가중합에서 빼고 남은 가중치를 다시 나눈다.
 */
public record ItemScore(String item, double score, boolean available, Reason reason) {

    public static ItemScore of(String item, double score, Reason reason) {
        return new ItemScore(item, clamp(score), true, reason);
    }

    public static ItemScore unavailable(String item, String text) {
        return new ItemScore(item, 0, false, Reason.check(item, text));
    }

    static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
