package com.zipick.user.controller;

import com.zipick.global.auth.CurrentUser;
import com.zipick.global.response.ApiResponse;
import com.zipick.user.dto.UserPreferenceRequest;
import com.zipick.user.dto.UserPreferenceResponse;
import com.zipick.user.dto.UserProfileRequest;
import com.zipick.user.dto.UserProfileResponse;
import com.zipick.user.service.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "사용자 정보", description = "추천에 쓰는 기본 정보·희망 주거조건 (사전 기입)")
@RestController
@RequestMapping("/api/v1/profiles/me")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService service;

    @Operation(summary = "기본 정보 조회")
    @GetMapping
    public ApiResponse<UserProfileResponse> getProfile(@CurrentUser Long userId) {
        return ApiResponse.ok(service.getProfile(userId));
    }

    @Operation(summary = "기본 정보 저장 (나이·혼인·세대·소득·자산·무주택)")
    @PutMapping
    public ApiResponse<UserProfileResponse> saveProfile(@CurrentUser Long userId,
                                                        @Valid @RequestBody UserProfileRequest request) {
        return ApiResponse.ok(service.saveProfile(userId, request));
    }

    @Operation(summary = "희망 주거조건 조회")
    @GetMapping("/preferences")
    public ApiResponse<UserPreferenceResponse> getPreference(@CurrentUser Long userId) {
        return ApiResponse.ok(service.getPreference(userId));
    }

    @Operation(summary = "희망 주거조건 저장 (관심 지역·보증금·월세·면적·유형·예산)")
    @PutMapping("/preferences")
    public ApiResponse<UserPreferenceResponse> savePreference(@CurrentUser Long userId,
                                                              @Valid @RequestBody UserPreferenceRequest request) {
        return ApiResponse.ok(service.savePreference(userId, request));
    }
}
