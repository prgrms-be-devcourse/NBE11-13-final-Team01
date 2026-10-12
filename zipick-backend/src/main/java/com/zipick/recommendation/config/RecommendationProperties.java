package com.zipick.recommendation.config;

import com.zipick.housing.domain.RentalType;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 추천 알고리즘 설정값. application.yml 의 zipick.recommendation 과 매핑된다.
 * 숫자를 코드에 직접 쓰지 않고 여기서 조정한다.
 */
@ConfigurationProperties(prefix = "zipick.recommendation")
public record RecommendationProperties(
        int minScore,
        int maxResults,
        int maxPerAnnouncement,
        int minResultsBeforeRelax,
        double checkMultiplier,
        BigDecimal defaultConversionRate,
        Map<String, Integer> baseWeights,
        double costOverBudgetZeroRatio,
        RegionScores regionScores,
        int areaStepM2,
        int infraFullScoreM,
        int infraZeroScoreM,
        double minCoverage,
        Map<RentalType, EligibilityRule> eligibility
) {
    public record RegionScores(double rank1, double rank2, double rank3) {}

    /**
     * 공공임대 유형별 지원조건. null 인 항목은 검사하지 않는다.
     * 금액은 만 원. 값은 참고용이며 최종 자격은 공고문 기준이다.
     */
    public record EligibilityRule(
            Integer minAge,
            Integer maxAge,
            Boolean requiresHomeless,
            Integer maxMonthlyIncome,
            Integer maxTotalAssets,
            Integer maxCarValue,
            Boolean marriedBypassesAge
    ) {}
}
