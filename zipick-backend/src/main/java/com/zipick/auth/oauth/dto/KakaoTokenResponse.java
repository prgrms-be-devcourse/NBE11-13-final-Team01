package com.zipick.auth.oauth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 카카오 토큰 교환 응답.
 *
 * 이 토큰은 사용자 정보 조회 1회에만 쓰고 저장하지 않는다.
 * refresh_token·id_token 도 내려오지만 쓰지 않으므로 받지 않는다.
 */
public record KakaoTokenResponse(
        @JsonProperty("access_token") String accessToken
) {
}
