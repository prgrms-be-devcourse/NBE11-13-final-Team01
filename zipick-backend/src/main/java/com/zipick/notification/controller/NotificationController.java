package com.zipick.notification.controller;

import com.zipick.global.auth.CurrentUser;
import com.zipick.global.response.ApiResponse;
import com.zipick.notification.dto.NotificationEventResponse;
import com.zipick.notification.dto.NotificationSettingRequest;
import com.zipick.notification.dto.NotificationSettingResponse;
import com.zipick.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "알림", description = "관심 공고 마감·새 공고 알림. 슬랙 웹훅 / 이메일")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService service;

    @Operation(summary = "내 알림 설정")
    @GetMapping("/settings")
    public ApiResponse<NotificationSettingResponse> get(@CurrentUser Long userId) {
        return ApiResponse.ok(service.getSetting(userId));
    }

    @Operation(summary = "알림 설정 저장", description = "슬랙 웹훅은 https://hooks.slack.com/ 으로 시작해야 한다")
    @PutMapping("/settings")
    public ApiResponse<NotificationSettingResponse> put(@CurrentUser Long userId, @Valid @RequestBody NotificationSettingRequest request) {
        return ApiResponse.ok(service.saveSetting(userId, request));
    }

    @Operation(summary = "알림 내역 (최근 50건)")
    @GetMapping
    public ApiResponse<List<NotificationEventResponse>> history(@CurrentUser Long userId) {
        return ApiResponse.ok(service.history(userId));
    }
}
