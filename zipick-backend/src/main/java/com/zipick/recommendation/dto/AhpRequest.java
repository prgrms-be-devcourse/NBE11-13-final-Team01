package com.zipick.recommendation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * AHP 쌍대 비교 답.
 *
 * value: left 가 right 보다 2 훨씬 중요 / 1 더 중요 / 0 비슷 / -1 right 가 더 / -2 right 가 훨씬
 * apply: true 면 활성 추천 기준의 우선순위에 바로 반영, false 면 계산 결과만 돌려준다(미리보기)
 */
public record AhpRequest(
        @NotEmpty @Size(max = 10) List<@Valid Comparison> comparisons,
        boolean apply
) {
    public record Comparison(
            @NotBlank String left,
            @NotBlank String right,
            @Min(-2) @Max(2) int value
    ) {}
}
