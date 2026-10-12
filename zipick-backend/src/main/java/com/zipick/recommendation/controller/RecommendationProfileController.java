package com.zipick.recommendation.controller;

import com.zipick.global.auth.CurrentUser;
import com.zipick.global.response.ApiResponse;
import com.zipick.recommendation.dto.RecommendationProfileRequest;
import com.zipick.recommendation.dto.RecommendationProfileResponse;
import com.zipick.recommendation.service.RecommendationProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "추천 기준", description = "가중치·꼭/되면 좋음 규칙·인프라 가중치. 사용자당 최대 3개")
@RestController
@RequestMapping("/api/v1/recommendation-profiles")
@RequiredArgsConstructor
public class RecommendationProfileController {

    private final RecommendationProfileService service;

    @Operation(summary = "내 추천 기준 목록")
    @GetMapping
    public ApiResponse<List<RecommendationProfileResponse>> list(@CurrentUser Long userId) {
        return ApiResponse.ok(service.list(userId));
    }

    @Operation(summary = "추천 기준 생성 (첫 기준은 자동 활성)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RecommendationProfileResponse> create(@CurrentUser Long userId,
                                                             @Valid @RequestBody RecommendationProfileRequest request) {
        return ApiResponse.ok(service.create(userId, request));
    }

    @Operation(summary = "추천 기준 수정 (가중치·규칙·인프라 가중치)")
    @PatchMapping("/{id}")
    public ApiResponse<RecommendationProfileResponse> update(@CurrentUser Long userId, @PathVariable Long id,
                                                             @Valid @RequestBody RecommendationProfileRequest request) {
        return ApiResponse.ok(service.update(userId, id, request));
    }

    @Operation(summary = "사용할 추천 기준 전환")
    @PostMapping("/{id}/activate")
    public ApiResponse<RecommendationProfileResponse> activate(@CurrentUser Long userId, @PathVariable Long id) {
        return ApiResponse.ok(service.activate(userId, id));
    }

    @Operation(summary = "추천 기준 삭제")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@CurrentUser Long userId, @PathVariable Long id) {
        service.delete(userId, id);
        return ApiResponse.ok();
    }
}
