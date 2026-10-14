package com.zipick.auth.dto.response;

// 재발급 응답. Refresh Token은 쿠키로 다시 내려가고 바디에는 Access Token만 담는다.
public record TokenResponse(
        String accessToken
) {
}
