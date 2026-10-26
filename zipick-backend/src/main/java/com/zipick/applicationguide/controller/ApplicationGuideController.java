package com.zipick.applicationguide.controller;

import com.zipick.applicationguide.dto.ApplicationGuideResponse;
import com.zipick.applicationguide.service.ApplicationGuideService;
import com.zipick.global.auth.CurrentUser;
import com.zipick.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 맞춤 신청 준비 가이드.
 *
 * 경로를 interest-notices 아래에 둔 이유는, 관심 공고로 저장한 공고에서만 쓸 수 있는 기능이라
 * 그 전제가 주소에 드러나는 편이 읽기 쉽기 때문이다. (검증은 당연히 서버에서 따로 한다)
 *
 * GET 과 POST 를 나눈 이유는 <b>AI 호출 시점을 사용자가 정하게 하기 위해서</b>다.
 * 화면에 들어오기만 해도 무료 사용량이 깎이면 안 된다.
 *   GET  저장된 결과만 읽는다. Gemini 를 절대 부르지 않는다.
 *   POST 사용자가 '만들기' 를 눌렀을 때만. 저장된 결과가 아직 쓸 수 있으면 그것을 돌려준다.
 */
@Tag(name = "AI 신청 준비 가이드", description = "관심 공고의 모집공고문을 읽어 내 상황에 맞는 제출서류·확인사항을 정리한다")
@RestController
@RequestMapping("/api/v1/interest-notices/{noticeId}/application-guide")
@RequiredArgsConstructor
public class ApplicationGuideController {

    private final ApplicationGuideService service;

    @Operation(summary = "저장된 가이드 조회", description = "AI 를 호출하지 않는다. 없으면 status=NOT_GENERATED")
    @GetMapping
    public ApiResponse<ApplicationGuideResponse> find(@CurrentUser Long userId, @PathVariable Long noticeId) {
        return ApiResponse.ok(service.find(userId, noticeId));
    }

    @Operation(summary = "가이드 생성", description = "저장된 결과가 아직 유효하면 그것을 돌려주고, 아니면 공고문을 읽어 새로 만든다")
    @PostMapping
    public ApiResponse<ApplicationGuideResponse> generate(@CurrentUser Long userId, @PathVariable Long noticeId) {
        return ApiResponse.ok(service.generate(userId, noticeId));
    }
}
