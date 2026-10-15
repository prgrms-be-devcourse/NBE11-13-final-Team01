package com.zipick.auth.service;

import com.zipick.auth.entity.RefreshToken;
import com.zipick.auth.jwt.JwtProvider;
import com.zipick.auth.jwt.TokenStatus;
import com.zipick.auth.jwt.TokenType;
import com.zipick.auth.repository.RefreshTokenRepository;
import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.user.entity.User;
import com.zipick.user.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TokenService {

    private final JwtProvider jwtProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;

    public record TokenPair(
            String accessToken,
            String refreshToken
    ) {
    }

    // Access Token과 Refresh Token을 함께 발급하고 Refresh Token은 DB에 저장한다.
    @Transactional
    public TokenPair issueToken(User user) {

        String accessToken = jwtProvider.createAccessToken(user);
        String refreshToken = jwtProvider.createRefreshToken(user);

        saveRefreshToken(user.getId(), refreshToken);

        log.debug("Token issued. userId: {}", user.getId());

        return new TokenPair(accessToken, refreshToken);
    }

    // 쿠키로 받은 Refresh Token을 검증하고 새 토큰 쌍으로 교체한다(Rotation).
    @Transactional
    public TokenPair reissue(String refreshToken) {

        if (refreshToken == null) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_NOT_FOUND);
        }

        // 1. JWT 자체 검증. 서명·만료·발급자·토큰 용도를 확인한다.
        TokenStatus status = jwtProvider.validateToken(refreshToken, TokenType.REFRESH);

        if (status == TokenStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.EXPIRED_REFRESH_TOKEN);
        }
        if (status != TokenStatus.VALID) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        Long userId = jwtProvider.getUserId(refreshToken);

        // 2. 저장된 값과 대조한다. 로그아웃했거나 이미 교체된 토큰을 막는다.
        RefreshToken stored = refreshTokenRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));

        if (!stored.matches(refreshToken)) {
            log.warn("[AUTH] 저장된 Refresh Token과 일치하지 않습니다. userId: {}", userId);
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        // 3. 토큰의 정보는 오래됐을 수 있으므로 현재 사용자를 다시 조회한다.
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));

        TokenPair tokenPair = issueToken(user);

        log.info("[AUTH] 토큰 재발급 완료 userId: {}", userId);

        return tokenPair;
    }

    // 저장된 Refresh Token을 지워 즉시 무효화한다. 이미 없으면 아무 일도 하지 않는다.
    @Transactional
    public void logout(Long userId) {

        refreshTokenRepository.deleteByUserId(userId);

        log.info("[AUTH] 로그아웃 완료 userId: {}", userId);
    }

    // 사용자당 1개만 유지하므로 기존 토큰이 있으면 교체한다.
    private void saveRefreshToken(Long userId, String token) {

        LocalDateTime expiresAt = LocalDateTime.ofInstant(
                jwtProvider.getRefreshTokenExpiresAt(Instant.now()), ZoneId.systemDefault());

        refreshTokenRepository.findByUserId(userId)
                .ifPresentOrElse(
                        stored -> stored.update(token, expiresAt),
                        () -> refreshTokenRepository.save(RefreshToken.of(userId, token, expiresAt))
                );
    }
}
