package com.zipick.auth.oauth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 카카오 로그인(OAuth 2.0) 설정.
 *
 * 지도·장소 검색에 쓰는 kakao.local.* 과는 다른 용도다. 호스트도 다르다.
 *   kauth.kakao.com  인가·토큰 교환 (여기)
 *   kapi.kakao.com   사용자 정보 조회 (여기)
 *   dapi.kakao.com   주소·카테고리 검색 (kakao.local.*)
 *
 * 같은 카카오 앱을 쓰면 clientId 는 kakao.local.rest-api-key 와 같은 값이지만,
 * 나중에 앱을 분리할 수 있도록 설정 키는 따로 둔다.
 */
@ConfigurationProperties(prefix = "kakao.oauth")
public record KakaoOAuthProperties(
        String clientId,
        String clientSecret,
        String redirectUri,
        String authBaseUrl,
        String apiBaseUrl,
        Duration stateTtl
) {
    private static final String DEFAULT_AUTH_BASE_URL = "https://kauth.kakao.com";
    private static final String DEFAULT_API_BASE_URL = "https://kapi.kakao.com";
    private static final Duration DEFAULT_STATE_TTL = Duration.ofMinutes(5);

    public KakaoOAuthProperties {
        authBaseUrl = hasText(authBaseUrl) ? authBaseUrl.trim() : DEFAULT_AUTH_BASE_URL;
        apiBaseUrl = hasText(apiBaseUrl) ? apiBaseUrl.trim() : DEFAULT_API_BASE_URL;
        stateTtl = stateTtl != null ? stateTtl : DEFAULT_STATE_TTL;
    }

    /** 키가 없으면 카카오 로그인만 비활성화된다. 나머지 기능은 그대로 동작한다. */
    public boolean isConfigured() {
        return hasText(clientId) && !clientId.startsWith("YOUR_") && hasText(redirectUri);
    }

    public boolean hasClientSecret() {
        return hasText(clientSecret) && !clientSecret.startsWith("YOUR_");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
