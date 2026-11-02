package com.zipick.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.zipick.auth.dto.request.LoginRequest;
import com.zipick.auth.dto.request.SignupRequest;
import com.zipick.auth.dto.response.SignupResponse;
import com.zipick.auth.userdetails.CustomUserDetails;
import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.user.entity.Role;
import com.zipick.auth.userdetails.SocialAccountLoginException;
import com.zipick.user.entity.AuthProvider;
import com.zipick.user.entity.User;
import com.zipick.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String EMAIL = "test@example.com";
    private static final String RAW_PASSWORD = "password1234";
    private static final String ENCODED_PASSWORD = "encoded-password";
    private static final User USER = User.of(1L, EMAIL, ENCODED_PASSWORD, Role.ROLE_USER);

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private TokenService tokenService;

    @Mock
    private EmailVerificationService emailVerification;

    @InjectMocks
    private AuthService authService;

    @Nested
    @DisplayName("회원가입")
    class Signup {

        @Test
        @DisplayName("이메일 인증을 마치지 않았으면 가입하지 않는다")
        void notVerified() {
            org.mockito.Mockito.doThrow(new com.zipick.global.error.BusinessException(com.zipick.global.error.ErrorCode.EMAIL_NOT_VERIFIED))
                    .when(emailVerification).assertVerified(org.mockito.ArgumentMatchers.eq("test@example.com"), org.mockito.ArgumentMatchers.any());

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> authService.signup(new com.zipick.auth.dto.request.SignupRequest("test@example.com", "password1234", "bad-token")))
                    .isInstanceOf(com.zipick.global.error.BusinessException.class)
                    .extracting("errorCode").isEqualTo(com.zipick.global.error.ErrorCode.EMAIL_NOT_VERIFIED);
            org.mockito.Mockito.verify(userRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
        }

        private void givenNewUser() {
            given(userRepository.existsByEmail(EMAIL)).willReturn(false);
            given(passwordEncoder.encode(RAW_PASSWORD)).willReturn(ENCODED_PASSWORD);
            given(userRepository.save(any(User.class))).willReturn(USER);
        }

        private User savedUser() {
            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("성공하면 생성된 식별자와 이메일을 반환한다")
        void signup() {
            givenNewUser();

            SignupResponse response = authService.signup(new SignupRequest(EMAIL, RAW_PASSWORD, null));

            assertThat(response.userId()).isEqualTo(1L);
            assertThat(response.email()).isEqualTo(EMAIL);
        }

        @Test
        @DisplayName("비밀번호는 암호화해서 저장한다")
        void encodePassword() {
            givenNewUser();

            authService.signup(new SignupRequest(EMAIL, RAW_PASSWORD, null));

            assertThat(savedUser().getPassword()).isEqualTo(ENCODED_PASSWORD);
            assertThat(savedUser().getPassword()).isNotEqualTo(RAW_PASSWORD);
        }

        @Test
        @DisplayName("가입 시 일반 사용자 권한을 부여한다")
        void defaultRole() {
            givenNewUser();

            authService.signup(new SignupRequest(EMAIL, RAW_PASSWORD, null));

            assertThat(savedUser().getRole()).isEqualTo(Role.ROLE_USER);
        }

        @Test
        @DisplayName("이미 가입된 이메일이면 409로 거부하고 저장하지 않는다")
        void duplicateEmail() {
            given(userRepository.existsByEmail(EMAIL)).willReturn(true);

            assertThatThrownBy(() -> authService.signup(new SignupRequest(EMAIL, RAW_PASSWORD, null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.DUPLICATE_EMAIL);

            verify(userRepository, never()).save(any(User.class));
        }
    }

    @Nested
    @DisplayName("로그인")
    class Login {

        @Test
        @DisplayName("성공하면 Access Token과 Refresh Token을 발급한다")
        void login() {
            Authentication authentication = new UsernamePasswordAuthenticationToken(
                    new CustomUserDetails(USER), null);
            given(authenticationManager.authenticate(any(Authentication.class))).willReturn(authentication);
            given(tokenService.issueToken(USER))
                    .willReturn(new TokenService.TokenPair("access-token", "refresh-token"));

            TokenService.TokenPair tokenPair = authService.login(new LoginRequest(EMAIL, RAW_PASSWORD));

            assertThat(tokenPair.accessToken()).isEqualTo("access-token");
            assertThat(tokenPair.refreshToken()).isEqualTo("refresh-token");
        }

        @Test
        @DisplayName("비밀번호가 틀리면 401로 거부하고 토큰을 발급하지 않는다")
        void wrongPassword() {
            willThrow(new BadCredentialsException("bad credentials"))
                    .given(authenticationManager).authenticate(any(Authentication.class));

            assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "wrong-password")))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_LOGIN);

            verify(tokenService, never()).issueToken(any(User.class));
        }

        @Test
        @DisplayName("가입되지 않은 이메일도 같은 401 메시지로 응답한다")
        void unknownEmail() {
            willThrow(new BadCredentialsException("bad credentials"))
                    .given(authenticationManager).authenticate(any(Authentication.class));

            assertThatThrownBy(() -> authService.login(new LoginRequest("none@example.com", RAW_PASSWORD)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        @Test
        @DisplayName("카카오로 가입한 계정이면 카카오 로그인을 쓰라고 안내한다")
        void socialAccount() {
            // 비밀번호를 몇 번 다시 쳐도 성공할 수 없는 경우라 일반 실패와 구분해 알린다.
            willThrow(new SocialAccountLoginException(AuthProvider.KAKAO))
                    .given(authenticationManager).authenticate(any(Authentication.class));

            assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, RAW_PASSWORD)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.SOCIAL_ACCOUNT_LOGIN);

            verify(tokenService, never()).issueToken(any(User.class));
        }
    }

    @Nested
    @DisplayName("운영자 로그인")
    class AdminLogin {

        @Test
        @DisplayName("ROLE_ADMIN 이면 토큰을 발급한다")
        void admin() {
            User admin = User.of(2L, "admin@example.com", ENCODED_PASSWORD, Role.ROLE_ADMIN);
            given(authenticationManager.authenticate(any(Authentication.class)))
                    .willReturn(new UsernamePasswordAuthenticationToken(new CustomUserDetails(admin), null));
            given(tokenService.issueToken(admin)).willReturn(new TokenService.TokenPair("a", "r"));

            assertThat(authService.adminLogin(new LoginRequest("admin@example.com", RAW_PASSWORD)).accessToken()).isEqualTo("a");
        }

        @Test
        @DisplayName("일반 사용자는 비밀번호가 맞아도 일반 로그인 실패와 같은 401 로 거부하고 토큰을 발급하지 않는다")
        void userRejected() {
            given(authenticationManager.authenticate(any(Authentication.class)))
                    .willReturn(new UsernamePasswordAuthenticationToken(new CustomUserDetails(USER), null));

            assertThatThrownBy(() -> authService.adminLogin(new LoginRequest(EMAIL, RAW_PASSWORD)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_LOGIN);
            verify(tokenService, never()).issueToken(any(User.class));
        }
    }
}
