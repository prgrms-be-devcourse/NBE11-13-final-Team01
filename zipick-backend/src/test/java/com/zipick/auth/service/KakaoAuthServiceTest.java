package com.zipick.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.zipick.auth.oauth.KakaoOAuthClient;
import com.zipick.auth.oauth.KakaoOAuthProperties;
import com.zipick.auth.oauth.dto.KakaoUserResponse;
import com.zipick.auth.oauth.dto.KakaoUserResponse.KakaoAccount;
import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.user.entity.AuthProvider;
import com.zipick.user.entity.Role;
import com.zipick.user.entity.User;
import com.zipick.user.repository.UserRepository;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class KakaoAuthServiceTest {

    private static final String PROVIDER_ID = "4071234567";
    private static final String CODE = "kakao-authorization-code";
    private static final String KAKAO_TOKEN = "kakao-access-token";

    @Mock
    private KakaoOAuthClient kakaoOAuthClient;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TokenService tokenService;

    @Mock
    private KakaoUserRegistrar registrar;

    private KakaoAuthService kakaoAuthService;

    @BeforeEach
    void setUp() {
        // 설정값은 record 라 목으로 만들지 않고 실제 값을 넣는다.
        kakaoAuthService = serviceWith(new KakaoOAuthProperties("client-id", "client-secret",
                "http://localhost:5173/oauth/kakao/callback", null, null, null, Duration.ofMinutes(5)));
    }

    private KakaoAuthService serviceWith(KakaoOAuthProperties properties) {
        return new KakaoAuthService(kakaoOAuthClient, properties, userRepository, tokenService, registrar);
    }

    private void givenKakaoUser(String email, Boolean valid, Boolean verified) {
        given(kakaoOAuthClient.exchangeToken(CODE)).willReturn(KAKAO_TOKEN);
        given(kakaoOAuthClient.fetchUser(KAKAO_TOKEN))
                .willReturn(new KakaoUserResponse(Long.valueOf(PROVIDER_ID), new KakaoAccount(email, valid, verified)));
    }

    private void givenRegistrarEchoesBack() {
        given(registrar.register(any(), any()))
                .willAnswer(invocation -> User.ofKakao(invocation.getArgument(0), invocation.getArgument(1)));
        given(tokenService.issueToken(any(User.class)))
                .willReturn(new TokenService.TokenPair("access", "refresh"));
    }

    /** 등록기에 어떤 이메일을 넘겼는지 확인한다. 실제 저장과 제약 처리는 통합 테스트가 본다. */
    private String captureRegisteredEmail() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(registrar).register(eq(PROVIDER_ID), captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("최초 로그인")
    class FirstLogin {

        @Test
        @DisplayName("검증된 이메일을 주면 그 이메일로 가입한다")
        void createWithEmail() {
            givenKakaoUser("kakao@example.com", true, true);
            given(userRepository.findByProviderAndProviderId(AuthProvider.KAKAO, PROVIDER_ID))
                    .willReturn(Optional.empty());
            givenRegistrarEchoesBack();

            kakaoAuthService.login(CODE);

            assertThat(captureRegisteredEmail()).isEqualTo("kakao@example.com");
        }

        @Test
        @DisplayName("이메일을 주지 않아도 가입을 막지 않는다. 가짜 이메일도 만들지 않는다")
        void createWithoutEmail() {
            givenKakaoUser(null, true, true);
            given(userRepository.findByProviderAndProviderId(AuthProvider.KAKAO, PROVIDER_ID))
                    .willReturn(Optional.empty());
            givenRegistrarEchoesBack();

            kakaoAuthService.login(CODE);

            // 가짜 이메일을 만들어 넘기지 않는다.
            assertThat(captureRegisteredEmail()).isNull();
        }

        @Test
        @DisplayName("미인증 이메일은 신뢰하지 않고 null로 저장한다")
        void unverifiedEmailIsNotStored() {
            givenKakaoUser("kakao@example.com", true, false);
            given(userRepository.findByProviderAndProviderId(AuthProvider.KAKAO, PROVIDER_ID))
                    .willReturn(Optional.empty());
            givenRegistrarEchoesBack();

            kakaoAuthService.login(CODE);

            assertThat(captureRegisteredEmail()).isNull();
        }
    }

    @Test
    @DisplayName("재로그인은 이메일이 아니라 회원번호로 찾는다. 새로 가입시키지 않는다")
    void reLoginUsesProviderId() {
        // 카카오 쪽에서 이메일을 바꿨더라도 같은 계정이어야 한다.
        givenKakaoUser("changed@example.com", true, true);
        User existing = User.of(7L, "old@example.com", null, Role.ROLE_USER, AuthProvider.KAKAO, PROVIDER_ID);
        given(userRepository.findByProviderAndProviderId(AuthProvider.KAKAO, PROVIDER_ID))
                .willReturn(Optional.of(existing));
        given(tokenService.issueToken(existing))
                .willReturn(new TokenService.TokenPair("access", "refresh"));

        TokenService.TokenPair tokenPair = kakaoAuthService.login(CODE);

        assertThat(tokenPair.accessToken()).isEqualTo("access");
        verify(registrar, never()).register(any(), any());
        verify(tokenService).issueToken(existing);
    }

    @Test
    @DisplayName("검증된 카카오 이메일이 기존 계정과 같으면 자동 연결하지 않고 안내한다")
    void emailConflictIsNotLinked() {
        givenKakaoUser("taken@example.com", true, true);
        given(userRepository.findByProviderAndProviderId(AuthProvider.KAKAO, PROVIDER_ID))
                .willReturn(Optional.empty());
        given(registrar.register(PROVIDER_ID, "taken@example.com"))
                .willThrow(new BusinessException(ErrorCode.SOCIAL_EMAIL_ALREADY_REGISTERED));

        assertThatThrownBy(() -> kakaoAuthService.login(CODE))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SOCIAL_EMAIL_ALREADY_REGISTERED);

        // 기존 계정에 붙이지 않는다.
        verify(tokenService, never()).issueToken(any(User.class));
    }

    @Test
    @DisplayName("같은 카카오 계정이 동시에 처음 로그인하면 먼저 저장된 행을 쓴다")
    void concurrentFirstLogin() {
        givenKakaoUser(null, true, true);
        User winner = User.of(9L, null, null, Role.ROLE_USER, AuthProvider.KAKAO, PROVIDER_ID);

        // 1회차 조회는 비어 있어 둘 다 통과하고, 저장에서 UNIQUE 제약에 걸린 쪽이 다시 조회하면 먼저 들어간 행이 보인다.
        given(userRepository.findByProviderAndProviderId(AuthProvider.KAKAO, PROVIDER_ID))
                .willReturn(Optional.empty(), Optional.of(winner));
        given(registrar.register(any(), any()))
                .willThrow(new DataIntegrityViolationException("uk_users_provider"));
        given(tokenService.issueToken(winner))
                .willReturn(new TokenService.TokenPair("access", "refresh"));

        TokenService.TokenPair tokenPair = kakaoAuthService.login(CODE);

        assertThat(tokenPair.accessToken()).isEqualTo("access");
        verify(tokenService).issueToken(winner);
    }

    @Test
    @DisplayName("카카오 키가 없는 서버에서는 503으로 끊는다")
    void notConfigured() {
        KakaoAuthService unconfigured =
                serviceWith(new KakaoOAuthProperties(null, null, null, null, null, null, null));

        assertThatThrownBy(() -> unconfigured.login(CODE))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OAUTH_NOT_CONFIGURED);

        assertThatThrownBy(() -> unconfigured.authorizeUrl("state"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OAUTH_NOT_CONFIGURED);

        // 설정이 없으면 카카오를 호출조차 하지 않는다.
        verify(kakaoOAuthClient, never()).exchangeToken(any());
    }

    @Test
    @DisplayName("provider 행이 없는데 제약에 걸렸다면 이메일을 선점당한 것이므로 409 로 안내한다")
    void emailRaceDuringInsert() {
        // existsByEmail 검사와 INSERT 사이에 다른 요청이 그 이메일로 먼저 가입한 경우.
        givenKakaoUser("taken@example.com", true, true);
        given(userRepository.findByProviderAndProviderId(AuthProvider.KAKAO, PROVIDER_ID))
                .willReturn(Optional.empty(), Optional.empty());
        given(registrar.register(any(), any()))
                .willThrow(new DataIntegrityViolationException("uk_users_email"));

        assertThatThrownBy(() -> kakaoAuthService.login(CODE))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SOCIAL_EMAIL_ALREADY_REGISTERED);

        verify(tokenService, never()).issueToken(any(User.class));
    }
}
