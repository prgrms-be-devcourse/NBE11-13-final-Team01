package com.zipick.recommendation.controller;

import com.zipick.global.auth.CurrentUser;
import com.zipick.global.response.ApiResponse;
import com.zipick.recommendation.dto.RecommendationFilterParams;
import com.zipick.recommendation.dto.RecommendationResponse;
import com.zipick.recommendation.dto.RecommendedHouseResponse;
import com.zipick.recommendation.dto.RelaxSuggestionResponse;
import com.zipick.recommendation.service.RecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "추천", description = "사전 기입 정보 기반 공급주택 점수제 추천")
@RestController
@RequestMapping("/api/v1/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService service;

    @Operation(summary = "추천 목록",
            description = "필터를 통과한 집에 지원조건 판정 → 항목 점수 → 가중합을 적용해 점수순으로 돌려준다. "
                    + "쿼리 파라미터로 지역·보증금·월세·면적·주택유형·상태·기관·임대유형을 걸 수 있다.")
    @GetMapping
    public ApiResponse<RecommendationResponse> recommend(@CurrentUser Long userId,
                                                         @ModelAttribute RecommendationFilterParams params) {
        return ApiResponse.ok(service.recommend(userId, params.toFilter(), params.isPreview()));
    }

    @Operation(summary = "집 하나의 점수와 근거")
    @GetMapping("/{houseId}")
    public ApiResponse<RecommendedHouseResponse> scoreOne(@CurrentUser Long userId, @PathVariable Long houseId) {
        return ApiResponse.ok(service.scoreOne(userId, houseId));
    }

    @Operation(summary = "조건 완화 제안", description = "추천이 기준 개수 미만일 때 조건을 하나씩 풀면 몇 곳이 되는지 알려준다.")
    @GetMapping("/relax-suggestions")
    public ApiResponse<RelaxSuggestionResponse> relax(@CurrentUser Long userId,
                                                      @ModelAttribute RecommendationFilterParams params) {
        return ApiResponse.ok(service.relaxSuggestions(userId, params.toFilter()));
    }
}
