package com.zipick.recommendation.dto;

import java.util.Map;

/**
 * 쌍대 선택 학습 결과.
 *
 * @param ready             answered ≥ minRequired 면 적용 가능
 * @param currentPriorities 지금 쓰는 우선순위
 * @param learnedPriorities 선택에서 찾은 우선순위
 * @param learnedWeights    선택에서 찾은 최종 가중치 (합 1)
 * @param agreement         "비슷해요"를 뺀 답 중 학습 결과로 설명되는 비율 (0~1). 답이 없으면 null
 */
public record PairLearnResponse(
        int answered,
        int minRequired,
        boolean ready,
        Map<String, Integer> currentPriorities,
        Map<String, Integer> learnedPriorities,
        Map<String, Double> learnedWeights,
        Double agreement
) {}
