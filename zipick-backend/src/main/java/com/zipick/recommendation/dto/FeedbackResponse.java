package com.zipick.recommendation.dto;

import com.zipick.recommendation.domain.RecommendationFeedback;

/**
 * 피드백 등록 결과. 같은 이유가 2번 이상 쌓이면 조건 변경 제안(suggestion)을 함께 돌려준다.
 * 제안은 사용자가 수락할 때만 프론트가 희망 조건 API로 반영한다.
 */
public record FeedbackResponse(
        Long id,
        Long houseId,
        RecommendationFeedback.Type type,
        RecommendationFeedback.Reason reason,
        boolean hidden,
        Suggestion suggestion
) {
    public record Suggestion(String field, Object from, Object to, String message) {}
}
