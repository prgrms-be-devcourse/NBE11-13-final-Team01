package com.zipick.auth.oauth;

import com.zipick.external.myhome.config.YamlPropertySourceFactory;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 카카오 로그인용 RestClient 2개.
 *
 * 타임아웃을 거는 방식은 KakaoLocalApiConfig 와 같다.
 * RestClient.builder() 를 직접 쓰면 spring.http.clients.* 전역 설정이 적용되지 않으므로
 * 요청 팩토리를 직접 만들어 붙인다.
 *
 * 다만 값은 인프라 수집보다 짧다. 수집은 배치라 느려도 되지만
 * 로그인은 사용자가 화면 앞에서 기다리는 동기 요청이다.
 */
@Configuration
@EnableConfigurationProperties(KakaoOAuthProperties.class)
@PropertySource(
        value = {
                "file:./config/application-secret.yml",
                "file:./zipick-backend/config/application-secret.yml"
        },
        ignoreResourceNotFound = true,
        factory = YamlPropertySourceFactory.class
)
public class KakaoOAuthConfig {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    /** 인가 코드 → 카카오 토큰 교환 (kauth.kakao.com) */
    @Bean
    RestClient kakaoAuthRestClient(KakaoOAuthProperties properties) {
        return restClient(properties.authBaseUrl());
    }

    /** 카카오 사용자 정보 조회 (kapi.kakao.com) */
    @Bean
    RestClient kakaoApiRestClient(KakaoOAuthProperties properties) {
        return restClient(properties.apiBaseUrl());
    }

    private static RestClient restClient(String baseUrl) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()
        );
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
