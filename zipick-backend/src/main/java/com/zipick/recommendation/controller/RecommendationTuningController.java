package com.zipick.recommendation.controller;

import com.zipick.global.auth.CurrentUser;
import com.zipick.global.response.ApiResponse;
import com.zipick.recommendation.dto.AhpRequest;
import com.zipick.recommendation.dto.AhpResponse;
import com.zipick.recommendation.dto.PairChoiceRequest;
import com.zipick.recommendation.dto.PairLearnResponse;
import com.zipick.recommendation.dto.PairQuestionResponse;
import com.zipick.recommendation.dto.RecommendationProfileResponse;
import com.zipick.recommendation.service.RecommendationTuningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "우선순위 찾기", description = "AHP 질문과 두 집 비교(쌍대 선택 학습)로 추천 우선순위를 맞춘다")
@RestController
@RequestMapping("/api/v1/recommendation-tuning")
@RequiredArgsConstructor
public class RecommendationTuningController {

    private final RecommendationTuningService service;

    @Operation(summary = "AHP 질문 답으로 우선순위 계산",
            description = "비교한 항목끼리 모든 쌍에 답해야 한다(항목 3개면 3문항). apply=true면 활성 추천 기준에 저장한다.")
    @PostMapping("/ahp")
    public ApiResponse<AhpResponse> ahp(@CurrentUser Long userId, @Valid @RequestBody AhpRequest request) {
        return ApiResponse.ok(service.ahp(userId, request));
    }

    @Operation(summary = "다음에 비교할 두 집",
            description = "지금까지의 답으로 학습한 모델이 가장 헷갈려하는 쌍을 고른다. 더 없으면 left/right가 null.")
    @GetMapping("/pairs/next")
    public ApiResponse<PairQuestionResponse> nextPair(@CurrentUser Long userId) {
        return ApiResponse.ok(service.nextPair(userId));
    }

    @Operation(summary = "두 집 중 고르기", description = "LEFT / RIGHT / TIE(비슷해요). 저장 후 학습 결과를 돌려준다.")
    @PostMapping("/pairs")
    public ApiResponse<PairLearnResponse> choose(@CurrentUser Long userId, @Valid @RequestBody PairChoiceRequest request) {
        return ApiResponse.ok(service.choose(userId, request));
    }

    @Operation(summary = "학습 결과", description = "지금 우선순위와 선택에서 찾은 우선순위를 비교해 보여 준다.")
    @GetMapping("/pairs/result")
    public ApiResponse<PairLearnResponse> result(@CurrentUser Long userId) {
        return ApiResponse.ok(service.result(userId));
    }

    @Operation(summary = "학습 결과를 추천 기준에 적용")
    @PostMapping("/pairs/apply")
    public ApiResponse<RecommendationProfileResponse> apply(@CurrentUser Long userId) {
        return ApiResponse.ok(service.applyLearned(userId));
    }

    @Operation(summary = "고른 기록 초기화")
    @DeleteMapping("/pairs")
    public ApiResponse<Void> reset(@CurrentUser Long userId) {
        service.reset(userId);
        return ApiResponse.ok();
    }
}
