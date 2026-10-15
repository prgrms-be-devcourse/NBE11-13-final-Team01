package com.zipick.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import com.zipick.user.entity.Role;
import com.zipick.user.entity.User;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

// application.yml 의 키 이름이 JwtProperties 와 어긋나면 기동 시점에만 드러나고 단위 테스트로는 잡히지 않는다.
// 실제 설정 파일을 읽어 바인딩을 확인한다.
class JwtPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues("JWT_SECRET=dTi5G5huScKMi+z2Blk0PX+JE+p7SxcXOLRchok4ueA=");

    @Test
    @DisplayName("application.yml 의 jwt 설정이 JwtProperties 에 바인딩된다")
    void bindFromApplicationYml() {
        runner.run(context -> {
            JwtProperties properties = context.getBean(JwtProperties.class);

            assertThat(properties.issuer()).isEqualTo("zipick");
            assertThat(properties.secret()).isNotBlank();
            assertThat(properties.accessTokenExpiration()).isEqualTo(Duration.ofHours(1));
            assertThat(properties.refreshTokenExpiration()).isEqualTo(Duration.ofDays(7));
        });
    }

    @Test
    @DisplayName("바인딩된 설정으로 JwtProvider 를 만들 수 있다")
    void createProviderFromBoundProperties() {
        runner.run(context -> {
            JwtProvider jwtProvider = new JwtProvider(context.getBean(JwtProperties.class));
            jwtProvider.init();

            String token = jwtProvider.createAccessToken(User.of(1L, "test@example.com", null, Role.ROLE_USER));

            assertThat(jwtProvider.validateToken(token, TokenType.ACCESS)).isEqualTo(TokenStatus.VALID);
        });
    }

    @Configuration
    @EnableConfigurationProperties(JwtProperties.class)
    static class TestConfig {
    }
}
