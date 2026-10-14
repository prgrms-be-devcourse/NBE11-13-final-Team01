package com.zipick.user.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zipick.auth.jwt.JwtProvider;
import com.zipick.auth.jwt.JwtProviderFixture;
import com.zipick.global.config.CorsProperties;
import com.zipick.global.config.SecurityConfig;
import com.zipick.user.entity.Role;
import com.zipick.user.entity.User;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

// 보안 필터를 켠 채로 실행해 토큰 → 인증 → 컨트롤러까지 이어지는지 확인한다.
@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, UserControllerTest.TestConfig.class})
class UserControllerTest {

    private static final User USER = User.of(1L, "test@example.com", null, Role.ROLE_USER);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    private String accessToken;

    @BeforeEach
    void setUp() {
        accessToken = jwtProvider.createAccessToken(USER);
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        CorsProperties corsProperties() {
            return new CorsProperties(List.of("http://localhost:5173"));
        }

        @Bean
        JwtProvider jwtProvider() {
            return JwtProviderFixture.create();
        }
    }

    @Test
    @DisplayName("유효한 Access Token으로 호출하면 내 계정 정보를 반환한다")
    void getMyInfo() throws Exception {
        mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.email").value("test@example.com"));
    }

    @Test
    @DisplayName("토큰이 없으면 401을 반환한다")
    void withoutToken() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("인증이 필요합니다."));
    }

    @Test
    @DisplayName("위조된 토큰이면 401을 반환한다")
    void invalidToken() throws Exception {
        mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Refresh Token으로는 접근할 수 없다")
    void refreshTokenRejected() throws Exception {
        String refreshToken = jwtProvider.createRefreshToken(USER);

        mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Bearer 접두사가 없으면 401을 반환한다")
    void withoutBearerPrefix() throws Exception {
        mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, accessToken))
                .andExpect(status().isUnauthorized());
    }
}
