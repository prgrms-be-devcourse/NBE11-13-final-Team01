package com.zipick.auth.jwt;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(

        String issuer,
        // Base64로 인코딩된 HS256 서명 키
        String secret,
        Duration accessTokenExpiration,
        Duration refreshTokenExpiration
) {
}
