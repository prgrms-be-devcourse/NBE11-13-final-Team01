package com.zipick.auth.dto.response;

// Refresh Token은 응답 바디가 아니라 HttpOnly 쿠키로 내려간다.
public record LoginResponse(
        String accessToken
) {
}
