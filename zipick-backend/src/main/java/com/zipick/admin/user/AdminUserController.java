package com.zipick.admin.user;

import com.zipick.admin.dto.PageResponse;
import com.zipick.auth.dto.response.EmailVerificationSendResponse;
import com.zipick.global.auth.CurrentUser;
import com.zipick.global.response.ApiResponse;
import com.zipick.user.entity.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 운영자(ROLE_ADMIN)만. 회원 목록·상세·권한, 가입 이메일 인증 지원 */
@Tag(name = "운영: 회원")
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Validated
public class AdminUserController {

    private final AdminUserService service;

    @Operation(summary = "회원 목록 (이메일 검색). 온보딩 여부·알림 설정·관심 공고 수")
    @GetMapping("/users")
    public ApiResponse<PageResponse<AdminUserService.UserRow>> users(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.ok(service.users(q, page, size));
    }

    @Operation(summary = "회원 상세: 기본 정보·희망 조건·알림 설정·알림 내역")
    @GetMapping("/users/{id}")
    public ApiResponse<AdminUserService.UserDetail> detail(@PathVariable Long id) { return ApiResponse.ok(service.detail(id)); }

    public record RoleBody(@NotNull Role role) {}

    @Operation(summary = "권한 변경 (ROLE_USER / ROLE_ADMIN). 자기 자신은 내릴 수 없다")
    @PatchMapping("/users/{id}/role")
    public ApiResponse<AdminUserService.UserRow> role(@CurrentUser Long adminId, @PathVariable Long id, @Valid @RequestBody RoleBody body) {
        return ApiResponse.ok(service.changeRole(adminId, id, body.role()));
    }

    @Operation(summary = "가입을 끝내지 않은 이메일 인증 시도 목록")
    @GetMapping("/email-verifications")
    public ApiResponse<List<AdminUserService.VerificationRow>> verifications() { return ApiResponse.ok(service.verifications()); }

    @Operation(summary = "인증 코드 다시 보내기 (재전송 간격·일일 횟수 규칙 적용)")
    @PostMapping("/email-verifications/{email}/resend")
    public ApiResponse<EmailVerificationSendResponse> resend(@PathVariable String email) { return ApiResponse.ok(service.resend(email)); }

    @Operation(summary = "인증 기록 초기화 (틀린 횟수·일일 전송 횟수 리셋)")
    @DeleteMapping("/email-verifications/{email}")
    public ApiResponse<Void> reset(@PathVariable String email) {
        service.reset(email);
        return ApiResponse.ok();
    }
}
