package com.zipick.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.zipick.auth.entity.RefreshToken;
import com.zipick.auth.jwt.JwtProvider;
import com.zipick.auth.jwt.TokenStatus;
import com.zipick.auth.jwt.TokenType;
import com.zipick.auth.repository.RefreshTokenRepository;
import com.zipick.user.entity.Role;
import com.zipick.user.entity.User;
import com.zipick.user.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    private static final User USER = User.of(1L, "test@example.com", "encoded", Role.ROLE_USER);
    private static final Instant EXPIRES_AT = Instant.parse("2026-10-21T00:00:00Z");
    private static final String STORED_TOKEN = "stored-refresh-token";

    @Mock
    private JwtProvider jwtProvider;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private TokenService tokenService;

    private void givenIssuedTokens() {
        given(jwtProvider.createAccessToken(USER)).willReturn("new-access-token");
        given(jwtProvider.createRefreshToken(USER)).willReturn("new-refresh-token");
        given(jwtProvider.getRefreshTokenExpiresAt(any(Instant.class))).willReturn(EXPIRES_AT);
    }

    private static RefreshToken storedToken() {
        return RefreshToken.of(1L, STORED_TOKEN, LocalDateTime.of(2026, 10, 20, 0, 0));
    }

    private static void assertUnauthorized(Throwable thrown) {
        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) thrown).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Nested
    @DisplayName("토큰 발급")
    class IssueToken {

        @Test
        @DisplayName("Access Token과 Refresh Token을 함께 발급한다")
        void issueToken() {
            givenIssuedTokens();
            given(refreshTokenRepository.findByUserId(1L)).willReturn(Optional.empty());

            TokenService.TokenPair tokenPair = tokenService.issueToken(USER);

            assertThat(tokenPair.accessToken()).isEqualTo("new-access-token");
            assertThat(tokenPair.refreshToken()).isEqualTo("new-refresh-token");
        }

        @Test
        @DisplayName("저장된 Refresh Token이 없으면 새로 저장한다")
        void saveWhenAbsent() {
            givenIssuedTokens();
            given(refreshTokenRepository.findByUserId(1L)).willReturn(Optional.empty());

            tokenService.issueToken(USER);

            ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
            verify(refreshTokenRepository).save(captor.capture());

            assertThat(captor.getValue().getUserId()).isEqualTo(1L);
            assertThat(captor.getValue().getToken()).isEqualTo("new-refresh-token");
        }

        @Test
        @DisplayName("저장된 Refresh Token이 있으면 새로 저장하지 않고 기존 행을 교체한다")
        void replaceWhenPresent() {
            givenIssuedTokens();
            RefreshToken stored = storedToken();
            given(refreshTokenRepository.findByUserId(1L)).willReturn(Optional.of(stored));

            tokenService.issueToken(USER);

            assertThat(stored.getToken()).isEqualTo("new-refresh-token");
            verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
        }
    }

    @Nested
    @DisplayName("재발급")
    class Reissue {

        private void givenValidRefreshToken() {
            given(jwtProvider.validateToken(STORED_TOKEN, TokenType.REFRESH)).willReturn(TokenStatus.VALID);
            given(jwtProvider.getUserId(STORED_TOKEN)).willReturn(1L);
        }

        @Test
        @DisplayName("성공하면 새 토큰 쌍을 발급하고 저장된 토큰을 교체한다")
        void reissue() {
            givenValidRefreshToken();
            RefreshToken stored = storedToken();
            given(refreshTokenRepository.findByUserId(1L)).willReturn(Optional.of(stored));
            given(userRepository.findById(1L)).willReturn(Optional.of(USER));
            givenIssuedTokens();

            TokenService.TokenPair tokenPair = tokenService.reissue(STORED_TOKEN);

            assertThat(tokenPair.accessToken()).isEqualTo("new-access-token");
            assertThat(tokenPair.refreshToken()).isEqualTo("new-refresh-token");
            // Rotation: 저장된 값도 새 토큰으로 바뀌어 이전 토큰은 재사용할 수 없다.
            assertThat(stored.getToken()).isEqualTo("new-refresh-token");
        }

        @Test
        @DisplayName("쿠키에 Refresh Token이 없으면 401을 반환한다")
        void missingToken() {
            assertUnauthorized(org.assertj.core.api.Assertions.catchThrowable(
                    () -> tokenService.reissue(null)));

            verify(refreshTokenRepository, never()).findByUserId(any());
        }

        @Test
        @DisplayName("만료된 Refresh Token이면 401을 반환한다")
        void expiredToken() {
            given(jwtProvider.validateToken(STORED_TOKEN, TokenType.REFRESH)).willReturn(TokenStatus.EXPIRED);

            assertThatThrownBy(() -> tokenService.reissue(STORED_TOKEN))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("만료된 Refresh Token");
        }

        @Test
        @DisplayName("위조된 Refresh Token이면 401을 반환한다")
        void invalidToken() {
            given(jwtProvider.validateToken(STORED_TOKEN, TokenType.REFRESH)).willReturn(TokenStatus.INVALID);

            assertUnauthorized(org.assertj.core.api.Assertions.catchThrowable(
                    () -> tokenService.reissue(STORED_TOKEN)));
        }

        @Test
        @DisplayName("저장된 Refresh Token이 없으면 401을 반환한다 (로그아웃 상태)")
        void notStored() {
            givenValidRefreshToken();
            given(refreshTokenRepository.findByUserId(1L)).willReturn(Optional.empty());

            assertUnauthorized(org.assertj.core.api.Assertions.catchThrowable(
                    () -> tokenService.reissue(STORED_TOKEN)));
        }

        @Test
        @DisplayName("저장된 값과 다른 Refresh Token이면 401을 반환한다 (이미 교체된 토큰)")
        void tokenMismatch() {
            given(jwtProvider.validateToken("old-token", TokenType.REFRESH)).willReturn(TokenStatus.VALID);
            given(jwtProvider.getUserId("old-token")).willReturn(1L);
            given(refreshTokenRepository.findByUserId(1L)).willReturn(Optional.of(storedToken()));

            assertUnauthorized(org.assertj.core.api.Assertions.catchThrowable(
                    () -> tokenService.reissue("old-token")));

            verify(userRepository, never()).findById(any());
        }

        @Test
        @DisplayName("사용자가 존재하지 않으면 401을 반환한다")
        void userNotFound() {
            givenValidRefreshToken();
            given(refreshTokenRepository.findByUserId(1L)).willReturn(Optional.of(storedToken()));
            given(userRepository.findById(1L)).willReturn(Optional.empty());

            assertUnauthorized(org.assertj.core.api.Assertions.catchThrowable(
                    () -> tokenService.reissue(STORED_TOKEN)));
        }
    }

    @Nested
    @DisplayName("로그아웃")
    class Logout {

        @Test
        @DisplayName("저장된 Refresh Token을 삭제한다")
        void logout() {
            tokenService.logout(1L);

            verify(refreshTokenRepository).deleteByUserId(1L);
        }
    }
}
