package com.zipick.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

// 로그인은 형식 검증만 하고 실제 판단은 인증 단계에 맡긴다.
// 가입 때와 같은 비밀번호 규칙을 걸면 규칙이 바뀌었을 때 기존 사용자가 로그인하지 못한다.
public record LoginRequest(

        @NotBlank(message = "이메일은 필수입니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수입니다.")
        String password
) {
}
