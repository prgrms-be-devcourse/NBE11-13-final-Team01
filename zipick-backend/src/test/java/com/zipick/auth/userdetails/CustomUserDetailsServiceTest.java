package com.zipick.auth.userdetails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.zipick.user.entity.Role;
import com.zipick.user.entity.User;
import com.zipick.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    private static final String EMAIL = "test@example.com";

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CustomUserDetailsService customUserDetailsService;

    @Test
    @DisplayName("이메일로 사용자를 찾으면 CustomUserDetails를 반환한다")
    void loadByEmail() {
        User user = User.of(1L, EMAIL, "encoded-password", Role.ROLE_USER);
        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(user));

        CustomUserDetails userDetails = customUserDetailsService.loadUserByUsername(EMAIL);

        assertThat(userDetails.getUsername()).isEqualTo(EMAIL);
        assertThat(userDetails.getPassword()).isEqualTo("encoded-password");
        assertThat(userDetails.getUser().getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("사용자가 없으면 UsernameNotFoundException이 발생한다")
    void userNotFound() {
        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.empty());

        assertThatThrownBy(() -> customUserDetailsService.loadUserByUsername(EMAIL))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
