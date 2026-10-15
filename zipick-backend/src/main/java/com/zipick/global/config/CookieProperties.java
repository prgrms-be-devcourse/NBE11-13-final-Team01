package com.zipick.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// secure는 HTTPS에서만 쿠키를 보낼지, sameSite는 다른 사이트에서 온 요청에 쿠키를 붙일지를 정한다.
// 환경마다 달라지므로 프로파일 설정으로 분리한다.
@ConfigurationProperties(prefix = "cookie")
public record CookieProperties(
        boolean secure,
        String sameSite
) {
}
