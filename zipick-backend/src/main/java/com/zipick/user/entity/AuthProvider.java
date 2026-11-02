package com.zipick.user.entity;

/**
 * 계정의 인증 수단.
 *
 * LOCAL = 이메일·비밀번호 가입, KAKAO = 카카오 소셜 로그인.
 * 둘은 서로 독립된 계정이다. 같은 사람이 양쪽으로 가입하면 별개의 user_id 를 갖는다
 * (자동·수동 계정 연동은 제공하지 않는다).
 */
public enum AuthProvider {
    LOCAL,
    KAKAO
}
