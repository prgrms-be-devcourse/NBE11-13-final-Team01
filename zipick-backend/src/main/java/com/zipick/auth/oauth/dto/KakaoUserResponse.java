package com.zipick.auth.oauth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 카카오 사용자 정보 (GET /v2/user/me).
 *
 * id 는 앱 단위로 고유한 회원번호다. 로그인에 쓰는 카카오 앱을 바꾸면 값이 전부 달라지므로
 * 앱을 교체하면 기존 소셜 계정이 끊긴다.
 *
 * 닉네임·프로필 사진은 수집하지 않는다(표시 정책상 쓰지 않는다).
 */
public record KakaoUserResponse(
        Long id,
        @JsonProperty("kakao_account") KakaoAccount kakaoAccount
) {

    public record KakaoAccount(
            String email,
            // 이메일이 카카오계정에서 유효한 상태인지 (탈퇴·변경 중이면 false)
            @JsonProperty("is_email_valid") Boolean emailValid,
            // 사용자가 소유를 인증한 이메일인지
            @JsonProperty("is_email_verified") Boolean emailVerified
    ) {
    }

    public String providerId() {
        return id == null ? null : String.valueOf(id);
    }

    /**
     * 신뢰할 수 있는 이메일만 돌려준다. 그 외에는 null 이며 users.email 도 null 로 저장한다.
     *
     * 미인증·무효 이메일을 그대로 쓰면 "검증된 이메일"이라는 전제가 깨지고,
     * 나중에 계정 연동을 붙일 때 남의 계정에 들어갈 수 있는 통로가 된다.
     */
    public String verifiedEmail() {
        if (kakaoAccount == null) {
            return null;
        }
        boolean trusted = Boolean.TRUE.equals(kakaoAccount.emailValid())
                && Boolean.TRUE.equals(kakaoAccount.emailVerified());

        if (!trusted || kakaoAccount.email() == null || kakaoAccount.email().isBlank()) {
            return null;
        }
        return kakaoAccount.email().trim();
    }
}
