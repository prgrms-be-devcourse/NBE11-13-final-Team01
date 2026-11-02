package com.zipick.auth.jwt;

import java.time.Duration;

// JwtProvider의 init()은 @PostConstruct라 Spring 밖에서는 직접 호출해야 하는데 package-private이다.
// 같은 패키지에 두고 다른 패키지 테스트가 쓸 수 있도록 공개 팩토리로 제공한다.
public final class JwtProviderFixture {

    public static final String ISSUER = "zipick";
    public static final String SECRET = "dTi5G5huScKMi+z2Blk0PX+JE+p7SxcXOLRchok4ueA=";

    private JwtProviderFixture() {
    }

    public static JwtProvider create() {
        return create(Duration.ofHours(1), Duration.ofDays(7));
    }

    public static JwtProvider create(Duration accessExpiration, Duration refreshExpiration) {
        JwtProvider jwtProvider = new JwtProvider(
                new JwtProperties(ISSUER, SECRET, accessExpiration, refreshExpiration));
        jwtProvider.init();
        return jwtProvider;
    }
}
