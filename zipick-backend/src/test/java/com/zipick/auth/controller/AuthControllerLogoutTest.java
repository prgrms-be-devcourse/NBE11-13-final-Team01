package com.zipick.auth.controller;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zipick.auth.jwt.JwtProperties;
import com.zipick.auth.jwt.JwtProvider;
import com.zipick.auth.jwt.JwtProviderFixture;
import com.zipick.auth.service.AuthService;
import com.zipick.auth.service.TokenService;
import com.zipick.global.config.CookieProperties;
import com.zipick.global.config.CorsProperties;
import com.zipick.global.config.SecurityConfig;
import com.zipick.global.util.CookieUtil;
import com.zipick.user.entity.Role;
import com.zipick.user.entity.User;
import java.time.Duration;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// 로그아웃은 인증이 필요한 엔드포인트라 보안 필터를 켜고 실제 토큰으로 확인한다.
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, CookieUtil.class, AuthControllerLogoutTest.TestConfig.class})
class AuthControllerLogoutTest {

    private static final User USER = User.of(1L, "test@example.com", null, Role.ROLE_USER);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private TokenService tokenService;

    @TestConfiguration
    static class TestConfig {

        @Bean
        CorsProperties corsProperties() {
            return new CorsProperties(List.of("http://localhost:5173"));
        }

        @Bean
        CookieProperties cookieProperties() {
            return new CookieProperties(false, "Lax");
        }

        @Bean
        JwtProperties jwtProperties() {
            return new JwtProperties("zipick", JwtProviderFixture.SECRET,
                    Duration.ofHours(1), Duration.ofDays(7));
        }

        @Bean
        JwtProvider jwtProvider() {
            return JwtProviderFixture.create();
        }
    }

    @Test
    @DisplayName("저장된 토큰을 지우고 쿠키를 만료시키며 204를 반환한다")
    void logout() throws Exception {
        String accessToken = jwtProvider.createAccessToken(USER);

        mockMvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, Matchers.containsString("refreshToken=;")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, Matchers.containsString("Max-Age=0")));

        verify(tokenService).logout(1L);
    }

    @Test
    @DisplayName("토큰 없이 호출하면 401을 반환하고 아무것도 지우지 않는다")
    void withoutToken() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isUnauthorized());

        verify(tokenService, never()).logout(org.mockito.ArgumentMatchers.anyLong());
    }
}
