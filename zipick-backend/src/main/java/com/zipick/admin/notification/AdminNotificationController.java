package com.zipick.admin.notification;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.global.response.ApiResponse;
import com.zipick.notification.client.NotificationClient;
import com.zipick.notification.dto.NotificationEventResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 운영자(ROLE_ADMIN)만. 알림 발송 이력은 Go 알림센터 DB 에 있어 HTTP 로 읽어 그대로 넘긴다.
 * 알림센터가 source 또는 tag 중 하나를 요구하므로 둘 다 비우면 source=main 으로 본다.
 */
@Tag(name = "운영: 알림 발송 이력")
@RestController
@RequestMapping("/api/v1/admin/notifications")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminNotificationController {

    private final NotificationClient client;

    @Operation(summary = "발송 이력 검색. source=main|collector, tag=user:123 처럼. userId 를 주면 tag=user:{id}")
    @GetMapping
    public ApiResponse<List<NotificationEventResponse>> search(
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) Long userId
    ) {
        if (userId != null) tag = "user:" + userId;
        if ((source == null || source.isBlank()) && (tag == null || tag.isBlank())) source = "main";
        if (tag != null && !tag.isBlank() && !tag.contains(":")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "tag 는 key:value 형식입니다 (예: user:12, kind=collector-report 는 kind:collector-report).");
        }
        return ApiResponse.ok(client.search(source, tag));
    }

    @Operation(summary = "메시지 하나 (채널별 전달 상태·오류)")
    @GetMapping("/{id}")
    public ApiResponse<NotificationEventResponse> get(@PathVariable long id) { return ApiResponse.ok(client.get(id)); }

    @Operation(summary = "알림센터에 켜진 채널 (email/slack). 알림센터에 닿지 않으면 null")
    @GetMapping("/channels")
    public ApiResponse<Map<String, Boolean>> channels() { return ApiResponse.ok(client.availableChannels()); }
}
