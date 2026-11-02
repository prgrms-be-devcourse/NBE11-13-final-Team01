package com.zipick.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 카카오 동의 화면 주소 조립.
 *
 * <p>scope 는 설정했을 때만 붙어야 한다. 카카오는 <b>앱에 권한이 없는 동의항목을 scope 로 요청하면
 * 동의 화면 대신 오류를 돌려주므로</b>, scope 를 무조건 붙이면 그 동의항목 권한을 받기 전까지
 * 로그인 자체가 막힌다. ZIPICK 앱은 카카오계정(이메일) 동의항목 권한이 없는 상태로 먼저 연다.
 *
 * <p>HTTP 호출이 없는 조립 로직이라 RestClient 는 쓰지 않는다.
 */
class KakaoAuthorizeUrlTest {

    private static final String STATE = "random-state";

    private static KakaoOAuthClient clientWithScope(String scope) {
        return new KakaoOAuthClient(null, null, new KakaoOAuthProperties(
                "client-id", "client-secret",
                "http://localhost:5173/oauth/kakao/callback",
                scope, null, null, Duration.ofMinutes(5)));
    }

    @ParameterizedTest(name = "scope=[{0}]")
    @DisplayName("scope 를 설정하지 않으면 인가 요청에 scope 를 붙이지 않는다")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void omitsScopeWhenNotConfigured(String scope) {
        String url = clientWithScope(scope).authorizeUrl(STATE);

        assertThat(url).doesNotContain("scope");
        // 나머지 필수 파라미터는 그대로 있어야 한다.
        assertThat(url)
                .startsWith("https://kauth.kakao.com/oauth/authorize")
                .contains("response_type=code")
                .contains("client_id=client-id")
                .contains("state=" + STATE)
                .contains("redirect_uri=http%3A%2F%2Flocalhost%3A5173%2Foauth%2Fkakao%2Fcallback");
    }

    @Test
    @DisplayName("scope 를 설정하면 인코딩해서 붙인다")
    void includesConfiguredScope() {
        // 여러 항목은 쉼표로 구분한다. 쉼표가 그대로 나가지 않도록 인코딩해야 한다.
        String url = clientWithScope("account_email,profile_nickname").authorizeUrl(STATE);

        assertThat(url).contains("&scope=account_email%2Cprofile_nickname");
    }

    @Test
    @DisplayName("hasScope 는 공백만 있는 값을 설정으로 보지 않는다")
    void blankScopeIsNotConfigured() {
        assertThat(props("   ").hasScope()).isFalse();
        assertThat(props(null).hasScope()).isFalse();
        assertThat(props("account_email").hasScope()).isTrue();
    }

    private static KakaoOAuthProperties props(String scope) {
        return new KakaoOAuthProperties("client-id", "secret", "http://localhost/cb",
                scope, null, null, Duration.ofMinutes(5));
    }
}
