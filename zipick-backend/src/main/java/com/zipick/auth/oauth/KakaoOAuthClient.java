package com.zipick.auth.oauth;

import com.zipick.auth.oauth.dto.KakaoTokenResponse;
import com.zipick.auth.oauth.dto.KakaoUserResponse;
import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 카카오 인증 서버 호출.
 *
 * 로그에 인가 코드·카카오 토큰·client secret 을 남기지 않는다.
 * 실패는 두 가지로 나눈다.
 *   4xx  = 코드가 이미 쓰였거나 만료됐다(사용자가 다시 시도하면 된다) -> OAUTH_CODE_INVALID
 *   그 외 = 카카오 장애·타임아웃 -> OAUTH_PROVIDER_ERROR
 */
@Slf4j
@Component
public class KakaoOAuthClient {

    private final RestClient authClient;
    private final RestClient apiClient;
    private final KakaoOAuthProperties properties;

    public KakaoOAuthClient(
            @Qualifier("kakaoAuthRestClient") RestClient authClient,
            @Qualifier("kakaoApiRestClient") RestClient apiClient,
            KakaoOAuthProperties properties
    ) {
        this.authClient = authClient;
        this.apiClient = apiClient;
        this.properties = properties;
    }

    /**
     * 사용자를 보낼 카카오 동의 화면 주소. redirect_uri 는 설정값으로 고정한다.
     *
     * <p>scope 는 설정했을 때만 붙인다. 앱에 권한이 없는 동의항목을 scope 로 요청하면
     * 카카오가 동의 화면 대신 오류를 돌려주기 때문이다(자세한 설명은 KakaoOAuthProperties#hasScope).
     */
    public String authorizeUrl(String state) {
        String url = properties.authBaseUrl() + "/oauth/authorize"
                + "?response_type=code"
                + "&client_id=" + encode(properties.clientId())
                + "&redirect_uri=" + encode(properties.redirectUri())
                + "&state=" + encode(state);

        return properties.hasScope() ? url + "&scope=" + encode(properties.scope()) : url;
    }

    public String exchangeToken(String code) {

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", properties.clientId());
        form.add("redirect_uri", properties.redirectUri());
        form.add("code", code);

        if (properties.hasClientSecret()) {
            form.add("client_secret", properties.clientSecret());
        }

        KakaoTokenResponse response = call(
                () -> authClient.post()
                        .uri("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(KakaoTokenResponse.class),
                "토큰 교환");

        if (response == null || response.accessToken() == null) {
            log.warn("[OAUTH] 카카오 토큰 응답에 access_token 이 없습니다");
            throw new BusinessException(ErrorCode.OAUTH_PROVIDER_ERROR);
        }
        return response.accessToken();
    }

    public KakaoUserResponse fetchUser(String kakaoAccessToken) {

        KakaoUserResponse response = call(
                () -> apiClient.get()
                        .uri("/v2/user/me")
                        .header("Authorization", "Bearer " + kakaoAccessToken)
                        .retrieve()
                        .body(KakaoUserResponse.class),
                "사용자 조회");

        if (response == null || response.providerId() == null) {
            log.warn("[OAUTH] 카카오 사용자 응답에 회원번호가 없습니다");
            throw new BusinessException(ErrorCode.OAUTH_PROVIDER_ERROR);
        }
        return response;
    }

    private <T> T call(java.util.function.Supplier<T> request, String step) {
        try {
            return request.get();

        } catch (RestClientResponseException e) {
            // 응답 본문에 카카오 오류 코드가 들어 있다. 인가 코드 자체는 본문에 없으므로 남겨도 된다.
            log.warn("[OAUTH] 카카오 {} 실패 status: {}, body: {}", step, e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(
                    e.getStatusCode().is4xxClientError() ? ErrorCode.OAUTH_CODE_INVALID : ErrorCode.OAUTH_PROVIDER_ERROR);

        } catch (RestClientException e) {
            // 타임아웃·연결 실패. 메시지에 URL 이 섞일 수 있어 예외 타입만 남긴다.
            log.warn("[OAUTH] 카카오 {} 통신 오류 type: {}", step, e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.OAUTH_PROVIDER_ERROR);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
