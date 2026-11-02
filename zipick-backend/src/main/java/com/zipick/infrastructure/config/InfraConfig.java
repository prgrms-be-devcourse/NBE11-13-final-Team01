package com.zipick.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 주변 인프라 수집 설정.
 *
 * 웹 서버와 수집기 프로세스 양쪽에서 쓴다. 수집기는 공고 수집 직후 HousingInfraCollectionService 를 직접 부른다.
 * 수집기에서 주변 인프라 수집 서비스를 호출한다.
 */
@Configuration
@EnableConfigurationProperties(InfraProperties.class)
public class InfraConfig {
}
