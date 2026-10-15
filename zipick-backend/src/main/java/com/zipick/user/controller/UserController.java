package com.zipick.user.controller;

import com.zipick.auth.userdetails.CustomUserDetails;
import com.zipick.global.response.ApiResponse;
import com.zipick.user.dto.response.UserInfoResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    // 조회 대상은 요청 파라미터가 아니라 토큰에서 꺼낸 사용자로 고정한다. 남의 정보를 조회할 수 없다.
    @GetMapping("/me")
    public ApiResponse<UserInfoResponse> getMyInfo(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return ApiResponse.ok(UserInfoResponse.from(userDetails.getUser()));
    }
}
