package com.zipick.auth.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zipick.auth.dto.request.LoginRequest;
import com.zipick.auth.dto.request.SignupRequest;
import com.zipick.auth.dto.response.SignupResponse;
import com.zipick.auth.jwt.JwtProperties;
import com.zipick.auth.service.AuthService;
import com.zipick.auth.service.TokenService;
import com.zipick.global.config.CookieProperties;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.server.ResponseStatusException;

// 보안 필터는 따로 검증하므로 여기서는 컨트롤러 계약만 확인한다.
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({CookieUtil.class, AuthControllerTest.TestConfig.class})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

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
                    .andExpect(jsonPath("$.userId").value(1))
                    .andExpect(jsonPath("$.email").value("test@example.com"));
        }

        @Test
        @DisplayName("이메일 형식이 올바르지 않으면 400을 반환한다")
        void invalidEmail() throws Exception {
            perform("/api/auth/signup", "{\"email\": \"not-an-email\", \"password\": \"password1234\"}")
                    .andExpect(status().isBadRequest());
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
                    .andExpect(jsonPath("$.accessToken").value("access-token"))
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
                    .andExpect(jsonPath("$.refreshToken").doesNotExist());
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
                    .andExpect(jsonPath("$.accessToken").value("new-access-token"))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE,
                            containsString("refreshToken=new-refresh-token")))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")));
        }

        @Test
        @DisplayName("쿠키가 없으면 서비스에 null을 넘겨 401로 거부된다")
        void withoutCookie() throws Exception {
            given(tokenService.reissue(null))
                    .willThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh Token이 없습니다."));

            mockMvc.perform(post("/api/auth/reissue"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("유효하지 않은 Refresh Token이면 401을 반환하고 쿠키를 교체하지 않는다")
        void invalidToken() throws Exception {
            given(tokenService.reissue("invalid-token"))
                    .willThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 Refresh Token입니다."));

            mockMvc.perform(post("/api/auth/reissue")
                            .cookie(new Cookie(CookieUtil.REFRESH_TOKEN_COOKIE, "invalid-token")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, not(containsString("refreshToken="))));
        }
    }
}
