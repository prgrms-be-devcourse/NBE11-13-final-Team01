package com.zipick.auth.jwt;

import com.zipick.auth.userdetails.CustomUserDetails;
import com.zipick.user.entity.Role;
import com.zipick.user.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import javax.crypto.SecretKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class JwtProvider {

    private static final String CLAIM_ID = "id";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TOKEN_TYPE = "type";

    private final JwtProperties jwtProperties;

    private SecretKey secretKey;
    private JwtParser jwtParser;

    @PostConstruct
    void init() {
        this.secretKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(jwtProperties.secret()));
        this.jwtParser = Jwts.parser()
                .verifyWith(secretKey)
                .requireIssuer(jwtProperties.issuer())
                .build();
    }

    public String createAccessToken(User user) {
        return createToken(user, TokenType.ACCESS, jwtProperties.accessTokenExpiration());
    }

    public String createRefreshToken(User user) {
        return createToken(user, TokenType.REFRESH, jwtProperties.refreshTokenExpiration());
    }

    // 서명·만료·발급자에 더해 토큰 용도까지 확인
    public TokenStatus validateToken(String token, TokenType expectedType) {
        try {
            Claims claims = jwtParser.parseSignedClaims(token).getPayload();

            if (!expectedType.name().equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
                log.warn("Token type mismatch. expected: {}", expectedType);
                return TokenStatus.INVALID;
            }
            return TokenStatus.VALID;

        } catch (ExpiredJwtException e) {
            log.warn("Token is expired");
            return TokenStatus.EXPIRED;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Token is invalid");
            return TokenStatus.INVALID;
        }
    }

    // 검증을 통과한 토큰에서 사용자 정보를 복구 (DB 조회 없이 인증 객체를 만들기 위한 용도)
    public User getTokenDetails(String token) {
        Claims claims = getClaims(token);

        return User.of(
                claims.get(CLAIM_ID, Long.class),
                claims.getSubject(),
                null,
                Role.valueOf(claims.get(CLAIM_ROLE, String.class))
        );
    }

    // 인가 판단은 이메일(sub)이 아니라 변하지 않는 식별자로 한다.
    public Long getUserId(String token) {
        return getClaims(token).get(CLAIM_ID, Long.class);
    }

    // 검증을 통과한 토큰으로 SecurityContext에 담을 인증 객체를 만든다.
    public Authentication getAuthentication(User user, String token) {

        CustomUserDetails principal = new CustomUserDetails(user);

        return new UsernamePasswordAuthenticationToken(principal, token, principal.getAuthorities());
    }

    // Refresh Token을 DB에 저장할 때 만료 시각 필요
    public Instant getRefreshTokenExpiresAt(Instant issuedAt) {
        return issuedAt.plus(jwtProperties.refreshTokenExpiration());
    }

    private Claims getClaims(String token) {
        return jwtParser.parseSignedClaims(token).getPayload();
    }

    private String createToken(User user, TokenType tokenType, Duration validity) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(jwtProperties.issuer())
                .subject(user.getEmail())
                .claim(CLAIM_ID, user.getId())
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_TOKEN_TYPE, tokenType.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(validity)))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }
}
