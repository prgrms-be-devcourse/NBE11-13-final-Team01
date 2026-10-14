package com.zipick.auth.userdetails;

import static org.assertj.core.api.Assertions.assertThat;

import com.zipick.user.entity.Role;
import com.zipick.user.entity.User;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

class CustomUserDetailsTest {

    private final CustomUserDetails userDetails =
            new CustomUserDetails(User.of(1L, "test@example.com", "encoded-password", Role.ROLE_USER));

    @Test
    @DisplayName("권한은 Role 이름을 그대로 사용한다")
    void authorities() {
        assertThat(userDetails.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("로그인 식별자는 이메일이다")
    void username() {
        assertThat(userDetails.getUsername()).isEqualTo("test@example.com");
    }

    @Test
    @DisplayName("계정 상태 관련 값은 모두 활성으로 고정한다")
    void alwaysActive() {
        Assertions.assertThat(userDetails.isAccountNonExpired()).isTrue();
        Assertions.assertThat(userDetails.isAccountNonLocked()).isTrue();
        Assertions.assertThat(userDetails.isCredentialsNonExpired()).isTrue();
        Assertions.assertThat(userDetails.isEnabled()).isTrue();
    }
}
