package com.zipick.auth.filter;

import com.zipick.auth.jwt.JwtProvider;
import com.zipick.auth.jwt.TokenStatus;
import com.zipick.auth.jwt.TokenType;
import com.zipick.user.entity.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

// 빈으로 등록하지 않는다. Filter 빈은 Boot가 서블릿 필터로도 자동 등록해 보안 체인 밖에서 한 번 더 돌고,
// 그 경우 SecurityContextHolderFilter가 인증 정보를 덮어쓴다. SecurityConfig에서 직접 생성해 체인에만 넣는다.
@Slf4j
@RequiredArgsConstructor
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String token = resolveToken(request);

        // 토큰이 없거나 무효여도 여기서 막지 않는다. 인증이 필요한 요청은 인가 단계에서 401로 처리된다.
        if (token != null && jwtProvider.validateToken(token, TokenType.ACCESS) == TokenStatus.VALID) {

            User user = jwtProvider.getTokenDetails(token);
            Authentication authentication = jwtProvider.getAuthentication(user, token);

            SecurityContextHolder.getContext().setAuthentication(authentication);

            log.debug("Authentication success. userId: {}, uri: {}", user.getId(), request.getRequestURI());
        }

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {

        String bearerToken = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (bearerToken != null && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }

        return null;
    }
}
