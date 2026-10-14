package com.zipick.external.myhome.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "myhome.api")
public record MyHomeApiProperties(
        String serviceKey,
        String noticeBaseUrl,
        String complexBaseUrl
) {
    private static final String DEFAULT_NOTICE_BASE_URL = "https://apis.data.go.kr/1613000/HWSPR02";
    private static final String DEFAULT_COMPLEX_BASE_URL = "https://apis.data.go.kr/1613000/HWSPR04";

    public MyHomeApiProperties {
        noticeBaseUrl = hasText(noticeBaseUrl) ? noticeBaseUrl.trim() : DEFAULT_NOTICE_BASE_URL;
        complexBaseUrl = hasText(complexBaseUrl) ? complexBaseUrl.trim() : DEFAULT_COMPLEX_BASE_URL;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
