package com.zipick.auth.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefreshTokenTest {

    private static final LocalDateTime EXPIRES_AT = LocalDateTime.of(2026, 10, 20, 0, 0);

    @Test
    @DisplayName("update하면 토큰과 만료 시각이 교체된다")
    void update() {
        RefreshToken refreshToken = RefreshToken.of(1L, "old-token", EXPIRES_AT);

        LocalDateTime newExpiresAt = EXPIRES_AT.plusDays(7);
        refreshToken.update("new-token", newExpiresAt);

        assertThat(refreshToken.getToken()).isEqualTo("new-token");
        assertThat(refreshToken.getExpiresAt()).isEqualTo(newExpiresAt);
        assertThat(refreshToken.getUserId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("matches는 저장된 토큰과 같을 때만 true를 반환한다")
    void matches() {
        RefreshToken refreshToken = RefreshToken.of(1L, "stored-token", EXPIRES_AT);

        assertThat(refreshToken.matches("stored-token")).isTrue();
        assertThat(refreshToken.matches("other-token")).isFalse();
    }
}
