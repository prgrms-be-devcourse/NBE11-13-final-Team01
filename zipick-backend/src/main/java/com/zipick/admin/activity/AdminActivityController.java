package com.zipick.admin.activity;

import com.zipick.admin.dto.PageResponse;
import com.zipick.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** 운영자(ROLE_ADMIN)만. 사용자 IP·화면 이동·클릭 기록 */
@Tag(name = "운영: 사용자 행동", description = "누가(회원/브라우저) 어떤 IP 로 어디까지 보고 눌렀는지")
@RestController
@RequestMapping("/api/v1/admin/activity")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Validated
public class AdminActivityController {

    private final ActivityService service;

    @Operation(summary = "최근 활동 사용자/브라우저 (마지막 IP·시각·화면, 이벤트 수)")
    @GetMapping("/actors")
    public ApiResponse<PageResponse<ActivityService.ActorRow>> actors(
            @RequestParam(defaultValue = "24") @Min(1) @Max(720) int hours,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.ok(service.recentActors(Duration.ofHours(hours), page, size));
    }

    @Operation(summary = "이벤트 목록 (회원·브라우저·IP·종류·경로로 거름)")
    @GetMapping("/events")
    public ApiResponse<PageResponse<ActivityService.ActivityRow>> events(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String anonymousId,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) Instant since,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size
    ) {
        return ApiResponse.ok(service.events(userId, anonymousId, ip, type, path, since, page, size));
    }

    @Operation(summary = "한 사용자(또는 브라우저)의 이동 경로 — 시간순, 최근 N건")
    @GetMapping("/trail")
    public ApiResponse<List<ActivityService.ActivityRow>> trail(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String anonymousId,
            @RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit
    ) {
        return ApiResponse.ok(service.trail(userId, anonymousId, limit));
    }
}
