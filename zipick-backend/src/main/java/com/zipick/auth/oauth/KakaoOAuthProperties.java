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
        String scope,
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

    /**
     * 인가 요청에 scope 를 붙일지.
     *
     * <p>카카오는 <b>앱에 권한이 없는 동의항목을 scope 로 요청하면 인가 요청 자체를 거부</b>한다.
     * 그래서 기본값은 "요청하지 않음"이다. scope 를 비워 두면 카카오 앱 콘솔에 설정된
     * 동의항목이 그대로 쓰인다.
     *
     * <p>카카오계정(이메일) 동의항목 권한을 받은 뒤에 {@code kakao.oauth.scope: account_email} 을
     * 켜면 된다. 이메일은 어차피 선택 사항이라, 받지 못해도 회원번호만으로 가입이 진행된다.
     */
    public boolean hasScope() {
        return hasText(scope);
    }

    public boolean hasClientSecret() {
        return hasText(clientSecret) && !clientSecret.startsWith("YOUR_");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
