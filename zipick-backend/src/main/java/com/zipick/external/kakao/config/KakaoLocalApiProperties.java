package com.zipick.external.kakao.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kakao.local")
public record KakaoLocalApiProperties(
        String restApiKey,
        String baseUrl
) {
    private static final String DEFAULT_BASE_URL = "https://dapi.kakao.com";

    public KakaoLocalApiProperties {
        baseUrl = hasText(baseUrl) ? baseUrl.trim() : DEFAULT_BASE_URL;
    }

    public boolean isConfigured() {
        return hasText(restApiKey) && !restApiKey.startsWith("YOUR_");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
