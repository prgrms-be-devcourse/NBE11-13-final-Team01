package com.zipick.user.controller;

import com.zipick.auth.userdetails.CustomUserDetails;
import com.zipick.global.response.ApiResponse;
import com.zipick.user.dto.response.UserInfoResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "내 계정", description = "로그인한 본인의 계정 정보")
@RestController
@RequestMapping("/api/users")
public class UserController {

    @Operation(summary = "내 정보 조회",
            description = "조회 대상은 Access Token에서 꺼낸 본인으로 고정된다. 남의 정보는 조회할 수 없다.")
    @GetMapping("/me")
    public ApiResponse<UserInfoResponse> getMyInfo(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return ApiResponse.ok(UserInfoResponse.from(userDetails.getUser()));
    }
}
