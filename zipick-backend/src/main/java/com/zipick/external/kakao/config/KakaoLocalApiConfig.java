package com.zipick.external.kakao.config;

import com.zipick.external.myhome.config.YamlPropertySourceFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(KakaoLocalApiProperties.class)
@PropertySource(
        value = {
                "file:./config/application-secret.yml",
                "file:./zipick-backend/config/application-secret.yml"
        },
        ignoreResourceNotFound = true,
        factory = YamlPropertySourceFactory.class
)
public class KakaoLocalApiConfig {

    /** TCP + TLS 핸드셰이크는 보통 수백 ms 안에 끝난다. 넉넉히 잡아도 3초면 충분하다. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /**
     * 목적은 응답을 빨리 끊는 것이 아니라 <b>무한 대기를 막는 것</b>이다.
     *
     * 이 클라이언트는 인프라 카테고리 검색과 주소→좌표 변환이 함께 쓴다.
     * 그중 좌표 변환(HousingGeocodingService)은 호출이 한 번만 실패해도 그 회차 전체를 중단하므로,
     * 일시적인 지연을 실패로 오해하지 않도록 넉넉하게 둔다.
     * 인프라 수집은 연속 3회 실패해야 회차를 멈추므로 최악의 경우에도 30초면 빠져나온다.
     */
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    RestClient kakaoLocalRestClient(KakaoLocalApiProperties properties) {
        return restClient(properties.baseUrl(), CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    /**
     * 타임아웃을 건 RestClient 를 만든다.
     *
     * RestClient.builder() 를 직접 쓰면 spring.http.clients.* 전역 설정이 적용되지 않으므로
     * 요청 팩토리를 직접 만들어 붙인다. 전역 설정 대신 이 방식을 쓰는 이유는
     * 다른 외부 API(공고 수집·슬랙 알림)는 응답 특성이 달라 같은 값을 강요하면 안 되기 때문이다.
     *
     * 연결 타임아웃은 HttpClient 가, 읽기 타임아웃은 요청 팩토리가 담당한다.
     * (첨부 다운로드용 ExternalDocumentClient 도 같은 JDK HttpClient 방식을 쓴다)
     *
     * 타임아웃이 실제로 걸리는지 짧은 값으로 검증할 수 있도록 분리해 두었다.
     */
    static RestClient restClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build()
        );
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
