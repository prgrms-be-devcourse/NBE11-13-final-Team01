package com.zipick.auth.dto.response;

/** 프론트가 그대로 이동할 카카오 동의 화면 주소. */
public record KakaoAuthorizeResponse(String authorizeUrl) {
}
