package com.zipick.applicationguide.config;

import com.zipick.external.myhome.config.YamlPropertySourceFactory;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Gemini 호출용 RestClient.
 *
 * <h3>왜 RestClient + JdkClientHttpRequestFactory 인가</h3>
 * 팀 Convention 은 "SDK 없이 Java HttpClient 로 REST 호출" 이다.
 * JdkClientHttpRequestFactory 의 전송 계층이 바로 java.net.http.HttpClient 라 그 Convention 을
 * 그대로 지키면서, 요청·응답 JSON 변환은 Spring 이 해 주어 코드가 짧아진다.
 * 카카오 로컬 API(KakaoLocalApiConfig)도 같은 방식이다.
 *
 * ⚠️ RestClient.builder() 를 직접 쓰면 spring.http.clients.* 전역 설정이 적용되지 않는다.
 *    타임아웃을 걸려면 요청 팩토리를 직접 만들어 붙여야 한다.
 *    전역 설정을 쓰지 않는 이유는 공고 수집·알림 등 다른 외부 호출과 응답 특성이 달라서다.
 *
 * 여기서 만드는 빈은 <b>이 기능 전용</b>이다. 공통으로 쓰는 것은 인증 키와 주소뿐이고
 * 타임아웃은 기능 설정(zipick.application-guide)에서 가져온다.
 *
 * <h3>비밀 값</h3>
 * 키는 환경변수 GEMINI_API_KEY 가 1순위다(relaxed binding 으로 gemini.api.key 에 들어간다).
 * 환경변수가 없을 때를 위해 로컬 비밀 파일도 함께 읽는다. 둘 다 없으면 AI 가이드 기능만 꺼진다.
 */
@Configuration
@PropertySource(
        value = {
                "file:./config/application-secret.yml",
                "file:./zipick-backend/config/application-secret.yml"
        },
        ignoreResourceNotFound = true,
        factory = YamlPropertySourceFactory.class
)
public class GeminiConfig {

    /**
     * 이 기능 전용 Gemini 클라이언트.
     *
     * 빈 이름에 기능을 붙인 이유는, 같은 Gemini 를 쓰더라도 타임아웃이 기능마다 달라야 하기 때문이다.
     * 공고 수집 보조 PDF 파서가 나중에 자기 클라이언트를 만들어도 이 빈과 충돌하지 않는다.
     * 주소(baseUrl)만 공통 설정에서 가져오고, 타임아웃은 이 기능 설정에서 가져온다.
     */
    @Bean
    RestClient applicationGuideGeminiRestClient(GeminiProperties gemini, ApplicationGuideProperties guide) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(guide.connectTimeout()).build()
        );
        requestFactory.setReadTimeout(guide.readTimeout());
        return RestClient.builder()
                .baseUrl(gemini.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    /** 타임아웃이 실제로 걸리는지 짧은 값으로 확인할 수 있게 열어 둔다 */
    static RestClient restClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build()
        );
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
