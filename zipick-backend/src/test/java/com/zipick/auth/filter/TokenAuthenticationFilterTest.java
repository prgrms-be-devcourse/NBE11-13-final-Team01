package com.zipick.auth.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.zipick.auth.jwt.JwtProvider;
import com.zipick.auth.jwt.JwtProviderFixture;
import com.zipick.auth.userdetails.CustomUserDetails;
import com.zipick.user.entity.Role;
import com.zipick.user.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

class TokenAuthenticationFilterTest {

    private static final User USER = User.of(1L, "test@example.com", null, Role.ROLE_USER);

    private final JwtProvider jwtProvider = JwtProviderFixture.create();
    private final TokenAuthenticationFilter filter = new TokenAuthenticationFilter(jwtProvider);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("유효한 Access Token이 있으면 SecurityContext에 인증 정보를 저장한다")
    void authenticateWithValidToken() throws Exception {
        String token = jwtProvider.createAccessToken(USER);

        doFilter(request -> request.addHeader("Authorization", "Bearer " + token));

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();

        CustomUserDetails principal = (CustomUserDetails) authentication.getPrincipal();
        assertThat(principal.getUsername()).isEqualTo("test@example.com");
        assertThat(principal.getUser().getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("토큰이 없으면 인증 정보를 저장하지 않는다")
    void noToken() throws Exception {
        doFilter(request -> {
        });

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Bearer 접두사가 없으면 인증 정보를 저장하지 않는다")
    void withoutBearerPrefix() throws Exception {
        String token = jwtProvider.createAccessToken(USER);

        doFilter(request -> request.addHeader("Authorization", token));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("위조된 토큰이면 인증 정보를 저장하지 않는다")
    void invalidToken() throws Exception {
        doFilter(request -> request.addHeader("Authorization", "Bearer not.a.jwt"));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Refresh Token으로는 인증되지 않는다")
    void refreshTokenRejected() throws Exception {
        String refreshToken = jwtProvider.createRefreshToken(USER);

        doFilter(request -> request.addHeader("Authorization", "Bearer " + refreshToken));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("인증 여부와 무관하게 다음 필터로 요청을 넘긴다")
    void alwaysContinuesChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    private void doFilter(java.util.function.Consumer<MockHttpServletRequest> customizer) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        customizer.accept(request);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }
}
