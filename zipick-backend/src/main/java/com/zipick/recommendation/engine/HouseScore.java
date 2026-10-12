package com.zipick.recommendation.engine;

import com.zipick.housing.domain.SupplyHouse;
import java.util.List;
import java.util.Map;

/**
 * 공급주택 하나의 최종 점수.
 *
 * @param score         0~100 추천도
 * @param itemScores    항목별 0~1 점수 (available=false면 계산 제외)
 * @param weights       정규화된 가중치 (합 1). 프론트가 슬라이더 재계산에 쓴다.
 * @param eligibility   지원조건 판정
 * @param reasons       근거 전체 (지원조건 + 항목)
 * @param excludedBy    MUST 규칙으로 제외됐다면 그 항목, 아니면 null
 */
public record HouseScore(
        SupplyHouse house,
        int score,
        Map<String, ItemScore> itemScores,
        Map<String, Double> weights,
        EligibilityResult eligibility,
        List<Reason> reasons,
        String excludedBy
) {
    public boolean excluded() {
        return eligibility.excluded() || excludedBy != null;
    }
}
