package com.zipick.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 주변 인프라 설정. 웹 서버는 조회(스냅샷 읽기)에, collector 프로세스(InfraCollectionApplication)는 수집에 쓴다.
 */
@Configuration
@EnableConfigurationProperties(InfraProperties.class)
public class InfraConfig {
}
