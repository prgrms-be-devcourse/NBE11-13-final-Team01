package com.zipick.recommendation.engine;

import com.zipick.housing.domain.Candidate;
import java.util.List;
import java.util.Map;

/**
 * 공급 호실 하나의 최종 점수.
 *
 * @param score         0~100 추천도. complete=false 면 참고값(순위에 쓰지 않음)
 * @param itemScores    항목별 0~1 점수 (available=false면 계산 제외)
 * @param weights       정규화된 가중치 (합 1). 프론트가 슬라이더 재계산에 쓴다.
 * @param eligibility   지원조건 판정
 * @param reasons       근거 전체 (지원조건 + 항목)
 * @param excludedBy    MUST 규칙으로 제외됐다면 그 항목, 아니면 null
 * @param complete      순위에 필요한 항목(required-items)이 모두 있으면 true. 아니면 '정보 부족' 묶음으로 간다
 */
public record HouseScore(
        Candidate candidate,
        int score,
        Map<String, ItemScore> itemScores,
        Map<String, Double> weights,
        EligibilityResult eligibility,
        List<Reason> reasons,
        String excludedBy,
        boolean complete
) {
    public boolean excluded() {
        return eligibility.excluded() || excludedBy != null;
    }

    public Long housingId() {
        return candidate.housingId();
    }
}
