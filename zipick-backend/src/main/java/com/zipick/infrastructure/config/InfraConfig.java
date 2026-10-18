package com.zipick.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 주변 인프라 수집 설정.
 *
 * 스케줄링은 웹 애플리케이션일 때만 켠다.
 * 공고 수집 전용 진입점(NoticeCollectionApplication)은 WebApplicationType.NONE 으로 뜨므로
 * 이 설정이 적용되지 않고, 공고 수집만 하고 종료된다.
 * 테스트는 @WebMvcTest 슬라이스만 쓰고 있어 이 @Configuration 자체가 로드되지 않는다.
 */
@Configuration
@EnableConfigurationProperties(InfraProperties.class)
@ConditionalOnWebApplication
@EnableScheduling
public class InfraConfig {
}
