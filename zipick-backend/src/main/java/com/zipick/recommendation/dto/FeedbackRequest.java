package com.zipick.recommendation.dto;

import com.zipick.recommendation.domain.RecommendationFeedback;
import jakarta.validation.constraints.NotNull;

public record FeedbackRequest(
        @NotNull Long houseId,
        @NotNull RecommendationFeedback.Type type,
        RecommendationFeedback.Reason reason
) {}
