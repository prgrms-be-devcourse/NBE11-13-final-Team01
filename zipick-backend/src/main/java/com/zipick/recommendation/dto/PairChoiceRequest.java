package com.zipick.recommendation.dto;

import com.zipick.recommendation.domain.PairwiseChoice;
import jakarta.validation.constraints.NotNull;

/** 두 집 중 고른 결과. 같은 쌍에 다시 답하면 덮어쓴다. */
public record PairChoiceRequest(
        @NotNull Long leftHouseId,
        @NotNull Long rightHouseId,
        @NotNull PairwiseChoice.Choice choice
) {}
