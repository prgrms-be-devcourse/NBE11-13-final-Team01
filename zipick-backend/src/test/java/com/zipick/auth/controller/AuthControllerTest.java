package com.zipick.auth.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zipick.auth.dto.request.LoginRequest;
import com.zipick.auth.dto.request.SignupRequest;
import com.zipick.auth.dto.response.EmailVerificationConfirmResponse;
import com.zipick.auth.dto.response.EmailVerificationSendResponse;
import com.zipick.auth.dto.response.SignupResponse;
import com.zipick.auth.jwt.JwtProperties;
import com.zipick.auth.service.AuthService;
import com.zipick.auth.service.EmailVerificationService;
import com.zipick.auth.service.TokenService;
import com.zipick.global.config.CookieProperties;
import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.global.error.GlobalExceptionHandler;
import com.zipick.global.util.CookieUtil;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// 보안 필터는 따로 검증하므로 여기서는 컨트롤러 계약만 확인한다.
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({CookieUtil.class, GlobalExceptionHandler.class, AuthControllerTest.TestConfig.class})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private EmailVerificationService emailVerificationService;

    @MockitoBean
    private TokenService tokenService;

    @TestConfiguration
    static class TestConfig {

        @Bean
        CookieProperties cookieProperties() {
            return new CookieProperties(false, "Lax");
        }

        @Bean
        JwtProperties jwtProperties() {
            return new JwtProperties("zipick", "test-secret", Duration.ofHours(1), Duration.ofDays(7));
        }
    }

    private ResultActions perform(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Nested
    @DisplayName("회원가입")
    class Signup {

        @Test
        @DisplayName("성공하면 201과 생성된 사용자 정보를 반환한다")
        void signup() throws Exception {
            given(authService.signup(any(SignupRequest.class)))
                    .willReturn(new SignupResponse(1L, "test@example.com"));

            perform("/api/auth/signup", "{\"email\": \"test@example.com\", \"password\": \"password1234\"}")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.userId").value(1))
                    .andExpect(jsonPath("$.data.email").value("test@example.com"));
        }

        @Test
        @DisplayName("이메일 형식이 올바르지 않으면 400을 반환한다")
        void invalidEmail() throws Exception {
            perform("/api/auth/signup", "{\"email\": \"not-an-email\", \"password\": \"password1234\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.data").doesNotExist())
                    .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.error.fields.email").exists());
        }

        @Test
        @DisplayName("JSON 형식이 잘못되면 내부 오류를 노출하지 않고 공통 400 응답을 반환한다")
        void malformedJson() throws Exception {
            perform("/api/auth/signup", "{\"email\":")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.error.message").value("요청 값이 올바르지 않습니다."));
        }

        @Test
        @DisplayName("비밀번호가 8자 미만이면 400을 반환한다")
        void shortPassword() throws Exception {
            perform("/api/auth/signup", "{\"email\": \"test@example.com\", \"password\": \"pw12\"}")
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("비밀번호에 숫자가 없으면 400을 반환한다")
        void passwordWithoutDigit() throws Exception {
            perform("/api/auth/signup", "{\"email\": \"test@example.com\", \"password\": \"passwordonly\"}")
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("비밀번호가 비어 있으면 400을 반환한다")
        void blankPassword() throws Exception {
            perform("/api/auth/signup", "{\"email\": \"test@example.com\", \"password\": \"\"}")
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("가입 이메일 인증")
    class EmailVerification {

        @Test
        @DisplayName("코드 보내기: 유효 시간과 재전송 간격을 돌려준다")
        void send() throws Exception {
            given(emailVerificationService.send("test@example.com")).willReturn(new EmailVerificationSendResponse(true, 10, 60));

            perform("/api/auth/email-verifications", "{\"email\": \"test@example.com\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.required").value(true))
                    .andExpect(jsonPath("$.data.expiresInMinutes").value(10))
                    .andExpect(jsonPath("$.data.resendAfterSeconds").value(60));
        }

        @Test
        @DisplayName("코드 보내기: 이메일 형식이 틀리면 400")
        void sendInvalidEmail() throws Exception {
            perform("/api/auth/email-verifications", "{\"email\": \"nope\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.fields.email").exists());
        }

        @Test
        @DisplayName("코드 보내기: 이미 가입한 이메일이면 409 DUPLICATE_EMAIL")
        void sendDuplicate() throws Exception {
            given(emailVerificationService.send("test@example.com")).willThrow(new BusinessException(ErrorCode.DUPLICATE_EMAIL));

            perform("/api/auth/email-verifications", "{\"email\": \"test@example.com\"}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("DUPLICATE_EMAIL"));
        }

        @Test
        @DisplayName("코드 확인: 맞으면 인증 완료 토큰을 돌려준다")
        void confirm() throws Exception {
            given(emailVerificationService.confirm("test@example.com", "123456")).willReturn(new EmailVerificationConfirmResponse("tok-abc", 10));

            perform("/api/auth/email-verifications/confirm", "{\"email\": \"test@example.com\", \"code\": \"123456\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.verificationToken").value("tok-abc"))
                    .andExpect(jsonPath("$.data.expiresInMinutes").value(10));
        }

        @Test
        @DisplayName("코드 확인: 6자리 숫자가 아니면 서비스를 부르지 않고 400")
        void confirmBadCode() throws Exception {
            perform("/api/auth/email-verifications/confirm", "{\"email\": \"test@example.com\", \"code\": \"12ab\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.fields.code").exists());
        }

        @Test
        @DisplayName("코드 확인: 틀리면 400 VERIFICATION_CODE_INVALID, 여러 번 틀리면 429")
        void confirmWrong() throws Exception {
            given(emailVerificationService.confirm("test@example.com", "000000")).willThrow(new BusinessException(ErrorCode.VERIFICATION_CODE_INVALID));
            given(emailVerificationService.confirm("test@example.com", "111111")).willThrow(new BusinessException(ErrorCode.VERIFICATION_TOO_MANY_ATTEMPTS));

            perform("/api/auth/email-verifications/confirm", "{\"email\": \"test@example.com\", \"code\": \"000000\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VERIFICATION_CODE_INVALID"));
            perform("/api/auth/email-verifications/confirm", "{\"email\": \"test@example.com\", \"code\": \"111111\"}")
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.error.code").value("VERIFICATION_TOO_MANY_ATTEMPTS"));
        }

        @Test
        @DisplayName("가입: 인증 완료 토큰이 없거나 틀리면 400 EMAIL_NOT_VERIFIED")
        void signupNotVerified() throws Exception {
            given(authService.signup(any(SignupRequest.class))).willThrow(new BusinessException(ErrorCode.EMAIL_NOT_VERIFIED));

            perform("/api/auth/signup", "{\"email\": \"test@example.com\", \"password\": \"password1234\", \"verificationToken\": \"wrong\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("EMAIL_NOT_VERIFIED"));
        }
    }

    @Nested
    @DisplayName("로그인")
    class Login {

        private static final String VALID_BODY =
                "{\"email\": \"test@example.com\", \"password\": \"password1234\"}";

        @Test
        @DisplayName("성공하면 Access Token은 바디로, Refresh Token은 HttpOnly 쿠키로 내려준다")
        void login() throws Exception {
            given(authService.login(any(LoginRequest.class)))
                    .willReturn(new TokenService.TokenPair("access-token", "refresh-token"));

            perform("/api/auth/login", VALID_BODY)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.accessToken").value("access-token"))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("refreshToken=refresh-token")))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Lax")));
        }

        @Test
        @DisplayName("Refresh Token은 응답 바디에 담기지 않는다")
        void refreshTokenNotInBody() throws Exception {
            given(authService.login(any(LoginRequest.class)))
                    .willReturn(new TokenService.TokenPair("access-token", "refresh-token"));

            perform("/api/auth/login", VALID_BODY)
                    .andExpect(jsonPath("$.data.refreshToken").doesNotExist());
        }

        @Test
        @DisplayName("이메일이 비어 있으면 400을 반환한다")
        void blankEmail() throws Exception {
            perform("/api/auth/login", "{\"email\": \"\", \"password\": \"password1234\"}")
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("검증에 실패하면 쿠키를 내려주지 않는다")
        void noCookieOnFailure() throws Exception {
            perform("/api/auth/login", "{\"email\": \"\", \"password\": \"\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, not(containsString("refreshToken"))));
        }
    }

    @Nested
    @DisplayName("재발급")
    class Reissue {

        @Test
        @DisplayName("성공하면 새 Access Token과 교체된 Refresh Token 쿠키를 내려준다")
        void reissue() throws Exception {
            given(tokenService.reissue("old-refresh-token"))
                    .willReturn(new TokenService.TokenPair("new-access-token", "new-refresh-token"));

            mockMvc.perform(post("/api/auth/reissue")
                            .cookie(new Cookie(CookieUtil.REFRESH_TOKEN_COOKIE, "old-refresh-token")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.accessToken").value("new-access-token"))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE,
                            containsString("refreshToken=new-refresh-token")))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")));
        }

        @Test
        @DisplayName("쿠키가 없으면 서비스에 null을 넘겨 401로 거부된다")
        void withoutCookie() throws Exception {
            given(tokenService.reissue(null))
                    .willThrow(new BusinessException(ErrorCode.REFRESH_TOKEN_NOT_FOUND));

            mockMvc.perform(post("/api/auth/reissue"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("유효하지 않은 Refresh Token이면 401을 반환하고 쿠키를 교체하지 않는다")
        void invalidToken() throws Exception {
            given(tokenService.reissue("invalid-token"))
                    .willThrow(new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));

            mockMvc.perform(post("/api/auth/reissue")
                            .cookie(new Cookie(CookieUtil.REFRESH_TOKEN_COOKIE, "invalid-token")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, not(containsString("refreshToken="))));
        }
    }
}
