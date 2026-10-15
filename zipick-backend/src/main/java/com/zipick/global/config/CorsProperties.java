package com.zipick.global.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 프론트엔드 주소는 환경마다 다르므로 설정으로 분리한다.
@ConfigurationProperties(prefix = "cors")
public record CorsProperties(List<String> allowedOrigins) {
}
