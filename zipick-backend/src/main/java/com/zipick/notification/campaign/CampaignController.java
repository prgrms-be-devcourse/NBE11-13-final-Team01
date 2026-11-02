package com.zipick.notification.campaign;

import com.zipick.global.auth.CurrentUser;
import com.zipick.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 운영자(ROLE_ADMIN)만. 계정을 운영자로 바꾸려면 users.role 을 'ROLE_ADMIN' 으로 (db/local-init.sql 참고) */
@Tag(name = "운영: 소식 메일 캠페인", description = "광고 수신 동의자에게 소식 메일. 21~08시는 피해서 예약된다")
@RestController
@RequestMapping("/api/v1/admin/campaigns")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class CampaignController {

    private final CampaignService service;

    @Operation(summary = "캠페인 목록 (최근 50)")
    @GetMapping
    public ApiResponse<List<CampaignResponse>> list() { return ApiResponse.ok(service.list()); }

    @Operation(summary = "캠페인 초안 만들기")
    @PostMapping
    public ApiResponse<CampaignResponse> create(@CurrentUser Long adminId, @Valid @RequestBody CampaignRequest request) {
        return ApiResponse.ok(service.create(adminId, request));
    }

    @Operation(summary = "캠페인 상태 + 발송 집계")
    @GetMapping("/{id}")
    public ApiResponse<CampaignResponse> get(@PathVariable Long id) { return ApiResponse.ok(service.get(id)); }

    @Operation(summary = "예약: 동의자에게 묶음 요청을 알림센터에 넘긴다")
    @PostMapping("/{id}/schedule")
    public ApiResponse<CampaignResponse> schedule(@PathVariable Long id) { return ApiResponse.ok(service.schedule(id)); }

    @Operation(summary = "취소: 아직 안 나간 메시지만 취소된다")
    @DeleteMapping("/{id}")
    public ApiResponse<CampaignResponse> cancel(@PathVariable Long id) { return ApiResponse.ok(service.cancel(id)); }
}
