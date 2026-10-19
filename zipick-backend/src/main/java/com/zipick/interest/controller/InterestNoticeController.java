package com.zipick.interest.controller;

import com.zipick.global.auth.CurrentUser;
import com.zipick.global.response.ApiResponse;
import com.zipick.interest.dto.InterestNoticeRequest;
import com.zipick.interest.dto.InterestNoticeResponse;
import com.zipick.interest.dto.ScheduleEventResponse;
import com.zipick.interest.service.InterestNoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관심 공고", description = "공고 저장과 일정(공고일·접수 시작·마감·당첨 발표)")
@RestController
@RequestMapping("/api/v1/interest-notices")
@RequiredArgsConstructor
public class InterestNoticeController {

    private final InterestNoticeService service;

    @Operation(summary = "내 관심 공고 목록 (최근 저장순)")
    @GetMapping
    public ApiResponse<List<InterestNoticeResponse>> list(@CurrentUser Long userId) {
        return ApiResponse.ok(service.list(userId));
    }

    @Operation(summary = "저장한 공고 id 목록 (카드의 ★ 표시용)")
    @GetMapping("/ids")
    public ApiResponse<List<Long>> ids(@CurrentUser Long userId) {
        return ApiResponse.ok(service.savedNoticeIds(userId));
    }

    @Operation(summary = "관심 공고 저장 (이미 있으면 메모만 갱신)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InterestNoticeResponse> save(@CurrentUser Long userId, @Valid @RequestBody InterestNoticeRequest request) {
        return ApiResponse.ok(service.save(userId, request));
    }

    @Operation(summary = "관심 공고 해제")
    @DeleteMapping("/{noticeId}")
    public ApiResponse<Void> remove(@CurrentUser Long userId, @PathVariable Long noticeId) {
        service.remove(userId, noticeId);
        return ApiResponse.ok();
    }

    @Operation(summary = "관심 공고 일정", description = "from·to(YYYY-MM-DD)를 주면 그 범위만. 날짜순")
    @GetMapping("/schedule")
    public ApiResponse<List<ScheduleEventResponse>> schedule(
            @CurrentUser Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(service.schedule(userId, from, to));
    }
}
