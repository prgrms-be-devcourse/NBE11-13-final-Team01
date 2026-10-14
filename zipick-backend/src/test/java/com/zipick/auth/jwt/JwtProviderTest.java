package com.zipick.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zipick.user.entity.Role;
import com.zipick.user.entity.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

// Spring 컨텍스트 없이 순수 단위 테스트로 수행한다.
// @PostConstruct는 Spring이 띄울 때만 실행되므로 테스트에서는 init()을 직접 호출한다.
class JwtProviderTest {

    private static final String ISSUER = "zipick";
    private static final String SECRET = "dTi5G5huScKMi+z2Blk0PX+JE+p7SxcXOLRchok4ueA=";
    private static final String OTHER_SECRET = "NB+NTTmujX+0DR3DG5Fy/R6ZXgNIjchhOw/ekH8nRrE=";
    private static final Long USER_ID = 1L;
    private static final String EMAIL = "test@example.com";
    private static final User USER = User.of(USER_ID, EMAIL, null, Role.ROLE_USER);

    private final JwtProvider jwtProvider = newProvider(Duration.ofHours(1), Duration.ofDays(7));

    private static JwtProvider newProvider(Duration accessExpiration, Duration refreshExpiration) {
        JwtProvider provider = new JwtProvider(
                new JwtProperties(ISSUER, SECRET, accessExpiration, refreshExpiration));
        provider.init();
        return provider;
    }

    @Nested
    @DisplayName("토큰 발급")
    class Create {

        @Test
        @DisplayName("Access Token을 발급하면 검증을 통과하고 사용자 식별자를 꺼낼 수 있다")
        void accessToken() {
            String token = jwtProvider.createAccessToken(USER);

            assertThat(jwtProvider.validateToken(token, TokenType.ACCESS)).isEqualTo(TokenStatus.VALID);
            assertThat(jwtProvider.getUserId(token)).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("Refresh Token을 발급하면 검증을 통과하고 사용자 식별자를 꺼낼 수 있다")
        void refreshToken() {
            String token = jwtProvider.createRefreshToken(USER);

            assertThat(jwtProvider.validateToken(token, TokenType.REFRESH)).isEqualTo(TokenStatus.VALID);
            assertThat(jwtProvider.getUserId(token)).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("서명 알고리즘은 HS256을 사용한다")
        void useHs256() {
            String token = jwtProvider.createAccessToken(USER);

            String header = new String(
                    Base64.getUrlDecoder().decode(token.substring(0, token.indexOf("."))), StandardCharsets.UTF_8);

            assertThat(header).contains("\"alg\":\"HS256\"");
        }

        @Test
        @DisplayName("Refresh Token 만료 시각은 설정한 유효기간만큼 뒤로 계산된다")
        void refreshTokenExpiresAt() {
            Instant issuedAt = Instant.parse("2026-10-13T00:00:00Z");

            assertThat(jwtProvider.getRefreshTokenExpiresAt(issuedAt))
                    .isEqualTo(Instant.parse("2026-10-20T00:00:00Z"));
        }
    }

    @Nested
    @DisplayName("토큰 검증")
    class Validate {

        @Test
        @DisplayName("만료된 토큰은 EXPIRED를 반환한다")
        void expired() {
            // 유효기간을 음수로 두어 이미 만료된 토큰을 만든다.
            JwtProvider expiredProvider = newProvider(Duration.ofSeconds(-10), Duration.ofDays(7));
            String token = expiredProvider.createAccessToken(USER);

            assertThat(jwtProvider.validateToken(token, TokenType.ACCESS)).isEqualTo(TokenStatus.EXPIRED);
        }

        @Test
        @DisplayName("다른 키로 서명한 토큰은 INVALID를 반환한다")
        void wrongSignature() {
            String token = signedToken(OTHER_SECRET, ISSUER, TokenType.ACCESS);

            assertThat(jwtProvider.validateToken(token, TokenType.ACCESS)).isEqualTo(TokenStatus.INVALID);
        }

        @Test
        @DisplayName("발급자가 다른 토큰은 INVALID를 반환한다")
        void wrongIssuer() {
            String token = signedToken(SECRET, "other-issuer", TokenType.ACCESS);

            assertThat(jwtProvider.validateToken(token, TokenType.ACCESS)).isEqualTo(TokenStatus.INVALID);
        }

        @Test
        @DisplayName("Refresh Token을 Access Token으로 사용하면 INVALID를 반환한다")
        void wrongTokenType() {
            String refreshToken = jwtProvider.createRefreshToken(USER);

            assertThat(jwtProvider.validateToken(refreshToken, TokenType.ACCESS)).isEqualTo(TokenStatus.INVALID);
        }

        @Test
        @DisplayName("형식이 깨진 토큰은 INVALID를 반환한다")
        void malformed() {
            assertThat(jwtProvider.validateToken("not.a.jwt", TokenType.ACCESS)).isEqualTo(TokenStatus.INVALID);
        }

        @Test
        @DisplayName("토큰이 비어 있어도 예외 없이 INVALID를 반환한다")
        void blank() {
            assertThat(jwtProvider.validateToken("", TokenType.ACCESS)).isEqualTo(TokenStatus.INVALID);
        }

        private String signedToken(String secret, String issuer, TokenType tokenType) {
            Instant now = Instant.now();
            return Jwts.builder()
                    .issuer(issuer)
                    .subject(EMAIL)
                    .claim("id", USER_ID)
                    .claim("role", Role.ROLE_USER.name())
                    .claim("type", tokenType.name())
                    .issuedAt(Date.from(now))
                    .expiration(Date.from(now.plus(Duration.ofHours(1))))
                    .signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret)), Jwts.SIG.HS256)
                    .compact();
        }
    }

    @Nested
    @DisplayName("서명 키")
    class SigningKey {

        @Test
        @DisplayName("HS256 최소 길이에 못 미치는 키는 기동 시점에 거부된다")
        void tooShort() {
            // 8바이트 키. Base64 디코딩 결과가 32바이트에 못 미친다.
            JwtProvider provider = new JwtProvider(
                    new JwtProperties(ISSUER, "hRgZw/zMsOo=", Duration.ofHours(1), Duration.ofDays(7)));

            assertThatThrownBy(provider::init).isInstanceOf(WeakKeyException.class);
        }

        @Test
        @DisplayName("Base64가 아닌 키는 기동 시점에 거부된다")
        void notBase64() {
            JwtProvider provider = new JwtProvider(
                    new JwtProperties(ISSUER, "not-base64-secret!!", Duration.ofHours(1), Duration.ofDays(7)));

            assertThatThrownBy(provider::init).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("토큰 클레임")
    class TokenClaims {

        @Test
        @DisplayName("sub에는 이메일, 클레임에는 식별자와 권한이 담긴다")
        void containsUserInfo() {
            String token = jwtProvider.createAccessToken(USER);

            User details = jwtProvider.getTokenDetails(token);

            assertThat(details.getEmail()).isEqualTo(EMAIL);
            assertThat(details.getId()).isEqualTo(USER_ID);
            assertThat(details.getRole()).isEqualTo(Role.ROLE_USER);
        }

        @Test
        @DisplayName("복구한 사용자 정보에는 비밀번호가 담기지 않는다")
        void withoutPassword() {
            String token = jwtProvider.createAccessToken(USER);

            assertThat(jwtProvider.getTokenDetails(token).getPassword()).isNull();
        }

        @Test
        @DisplayName("getUserId는 sub가 아니라 식별자 클레임을 읽는다")
        void userIdFromClaim() {
            String token = jwtProvider.createAccessToken(USER);

            assertThat(jwtProvider.getUserId(token)).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("Refresh Token에도 동일한 사용자 정보가 담긴다")
        void refreshTokenCarriesSameInfo() {
            String token = jwtProvider.createRefreshToken(USER);

            assertThat(jwtProvider.getUserId(token)).isEqualTo(USER_ID);
            assertThat(jwtProvider.getTokenDetails(token).getEmail()).isEqualTo(EMAIL);
        }
    }
}
