package com.zipick.recommendation.controller;

import com.zipick.global.auth.CurrentUser;
import com.zipick.global.response.ApiResponse;
import com.zipick.recommendation.dto.FeedbackRequest;
import com.zipick.recommendation.dto.FeedbackResponse;
import com.zipick.recommendation.service.RecommendationFeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "추천 피드백", description = "숨김·별로예요. 같은 이유가 반복되면 조건 변경을 제안한다")
@RestController
@RequestMapping("/api/v1/recommendation-feedbacks")
@RequiredArgsConstructor
public class RecommendationFeedbackController {

    private final RecommendationFeedbackService service;

    @Operation(summary = "피드백 등록 (해당 집은 추천에서 제외)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FeedbackResponse> submit(@CurrentUser Long userId,
                                                @Valid @RequestBody FeedbackRequest request) {
        return ApiResponse.ok(service.submit(userId, request));
    }

    @Operation(summary = "피드백 취소 (숨김 해제)")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@CurrentUser Long userId, @PathVariable Long id) {
        service.delete(userId, id);
        return ApiResponse.ok();
    }
}
