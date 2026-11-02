package com.zipick.admin.notice;

import com.zipick.admin.dto.PageResponse;
import com.zipick.global.response.ApiResponse;
import com.zipick.notice.domain.NoticeStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/** 운영자(ROLE_ADMIN)만. 공고 상태·주택 좌표·숨김·인프라 재수집 */
@Tag(name = "운영: 공고·주택 관리")
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Validated
public class AdminNoticeController {

    private final AdminNoticeService service;

    @Operation(summary = "공고 목록 (상태·출처·제목 검색)")
    @GetMapping("/notices")
    public ApiResponse<PageResponse<AdminNoticeService.NoticeRow>> notices(
            @RequestParam(required = false) List<NoticeStatus> status,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.ok(service.notices(status, source, q, page, size));
    }

    @Operation(summary = "공고 하나")
    @GetMapping("/notices/{id}")
    public ApiResponse<AdminNoticeService.NoticeRow> notice(@PathVariable Long id) { return ApiResponse.ok(service.notice(id)); }

    public record StatusBody(@NotNull NoticeStatus status) {}

    @Operation(summary = "공고 상태 변경 (statusReason=ADMIN). 목록에서 숨기려면 CLOSED/CANCELLED")
    @PatchMapping("/notices/{id}/status")
    public ApiResponse<AdminNoticeService.NoticeRow> changeStatus(@PathVariable Long id, @Valid @RequestBody StatusBody body) {
        return ApiResponse.ok(service.changeStatus(id, body.status()));
    }

    @Operation(summary = "공고의 주택 목록 (좌표·인프라 여부)")
    @GetMapping("/notices/{id}/housings")
    public ApiResponse<List<AdminNoticeService.HousingRow>> housingsOf(@PathVariable Long id) { return ApiResponse.ok(service.housingsOf(id)); }

    @Operation(summary = "좌표 미해결 주택 (활성인데 위경도 없음)")
    @GetMapping("/housings/unresolved")
    public ApiResponse<List<AdminNoticeService.HousingRow>> unresolved() { return ApiResponse.ok(service.unresolvedHousings()); }

    public record CoordinatesBody(@NotNull BigDecimal latitude, @NotNull BigDecimal longitude) {}

    @Operation(summary = "주택 좌표 직접 입력. 기존 인프라는 지워지고 다음 수집에서 다시 채운다")
    @PatchMapping("/housings/{id}/coordinates")
    public ApiResponse<AdminNoticeService.HousingRow> coordinates(@PathVariable Long id, @Valid @RequestBody CoordinatesBody body) {
        return ApiResponse.ok(service.updateCoordinates(id, body.latitude(), body.longitude()));
    }

    public record ActiveBody(@NotNull Boolean active) {}

    @Operation(summary = "주택 숨김/표시 (active)")
    @PatchMapping("/housings/{id}/active")
    public ApiResponse<AdminNoticeService.HousingRow> active(@PathVariable Long id, @Valid @RequestBody ActiveBody body) {
        return ApiResponse.ok(service.changeActive(id, body.active()));
    }

    @Operation(summary = "주변 인프라 재수집 예약 (스냅샷 삭제 → 다음 인프라 수집 대상)")
    @PostMapping("/housings/{id}/infra/refresh")
    public ApiResponse<AdminNoticeService.HousingRow> refreshInfra(@PathVariable Long id) { return ApiResponse.ok(service.refreshInfra(id)); }
}
