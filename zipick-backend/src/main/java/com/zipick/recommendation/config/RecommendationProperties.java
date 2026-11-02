package com.zipick.recommendation.config;

import com.zipick.housing.domain.RentalType;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 추천 알고리즘 설정값. application.yml 의 zipick.recommendation 과 매핑된다.
 * 숫자를 코드에 직접 쓰지 않고 여기서 조정한다. 각 값의 출처는 문서의 '근거 표'에 있다.
 */
@ConfigurationProperties(prefix = "zipick.recommendation")
public record RecommendationProperties(
        int minScore,
        int maxResults,
        int maxPerAnnouncement,
        int minResultsBeforeRelax,
        double checkMultiplier,
        double baseRate,
        Map<String, Integer> baseWeights,
        List<String> requiredItems,
        List<String> defaultMustItems,
        double costOverBudgetZeroRatio,
        RegionScores regionScores,
        int areaStepM2,
        Map<Integer, Integer> minimumAreaByHousehold,
        double roomStepPenalty,
        int infraFullScoreM,
        int infraZeroScoreM,
        int ageZeroYears,
        Map<Integer, Long> urbanWorkerIncome,
        Map<RentalType, EligibilityRule> eligibility
) {
    public record RegionScores(double rank1, double rank2, double rank3) {}

    /**
     * 공공임대 유형별 지원조건. null 인 항목은 검사하지 않는다. 금액은 만 원. 최종 자격은 공고문 기준이다.
     *
     * @param incomePercent       도시근로자 월평균소득 대비 % (키: 가구원 수, "default" = 나머지)
     * @param incomeHouseholdSize 소득 기준을 볼 가구원 수. null 이면 사용자 가구원 수 (청년 매입임대 3순위는 1인 기준)
     * @param requiresSingle      미혼(혼인 중이 아닌 사람)만 대상
     * @param marriedNote         기혼이면 이 계층 규칙 대신 다른 계층(신혼부부 등)일 수 있을 때 '확인 필요'로 보여 줄 말.
     *                            null 이면 기혼은 탈락
     * @param studentExtendsAge   대학생·취업준비생이면 연령 범위 밖이어도 대상
     * @param fallback            기준을 넘어도 다른 순위로 가능할 수 있으면 탈락 대신 '확인 필요'
     * @param note                규칙이 없거나 확인할 것이 있을 때 보여 줄 말
     */
    public record EligibilityRule(
            Integer minAge,
            Integer maxAge,
            Boolean requiresHomeless,
            Map<String, Integer> incomePercent,
            Integer incomeHouseholdSize,
            Integer maxTotalAssets,
            Integer maxCarValue,
            Boolean requiresSingle,
            String marriedNote,
            Boolean studentExtendsAge,
            Fallback fallback,
            String note
    ) {}

    public record Fallback(Integer maxTotalAssets, String note) {}

    /** 소득 상한 = 도시근로자 월평균소득(원) × 비율 → 만 원 */
    public Integer incomeLimitManwon(EligibilityRule rule, int householdSize) {
        if (rule.incomePercent() == null || urbanWorkerIncome == null) return null;
        int size = rule.incomeHouseholdSize() != null ? rule.incomeHouseholdSize() : householdSize;
        size = Math.max(1, Math.min(size, urbanWorkerIncome.keySet().stream().max(Integer::compare).orElse(1)));
        Long base = urbanWorkerIncome.get(size);
        Integer pct = rule.incomePercent().getOrDefault(String.valueOf(size), rule.incomePercent().get("default"));
        if (base == null || pct == null) return null;
        return (int) Math.floor(base * pct / 100.0 / 10_000.0);
    }

    /** 보증금 → 월 환산 이율(연 %) = min(10, 기준금리 + 2). 주택임대차보호법 시행령 제9조 */
    public double conversionRate() {
        return Math.min(10.0, baseRate + 2.0);
    }
}
