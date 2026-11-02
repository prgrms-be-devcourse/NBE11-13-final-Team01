package com.zipick.global.config;

import com.zipick.auth.filter.TokenAuthenticationFilter;
import com.zipick.auth.jwt.JwtProvider;
import com.zipick.global.error.ErrorCode;
import com.zipick.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtProvider jwtProvider;
    private final CorsProperties corsProperties;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        return http
                // JWT 기반이라 세션을 쓰지 않는다. 폼 로그인/기본 인증/기본 로그아웃도 직접 구현한다.
                .csrf(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/auth/signup",
                                "/api/auth/email-verifications",
                                "/api/auth/email-verifications/**",
                                "/api/auth/login",
                                "/api/auth/admin/login",
                                "/api/auth/reissue",
                                // 화면 진입·클릭 기록. 비로그인도 보낸다 (운영자 화면에서 조회)
                                "/api/v1/activity",
                                // 카카오 로그인 시작·완료. 둘 다 비로그인 상태에서 호출한다
                                "/api/auth/kakao/authorize",
                                "/api/auth/kakao/login",
                                "/error",
                                "/actuator/**",
                                // API 문서
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**"
                        ).permitAll()
                        // 비로그인도 조회 가능한 엔드포인트. 담당자 구현에 맞춰 조정한다.
                        .requestMatchers(HttpMethod.GET, "/api/v1/houses", "/api/v1/houses/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/map/housings", "/api/v1/map/housings/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/notices", "/api/notices/**").permitAll()
                        // TODO 내부·외부 연동 API. 운영에서는 별도 보호가 필요하다.
                        .requestMatchers("/api/internal/**", "/api/external/**").permitAll()
                        // 운영자 기능 (알림 캠페인 등). 컨트롤러의 @PreAuthorize 와 이중으로 막는다
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(new TokenAuthenticationFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration authenticationConfiguration
    ) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        // Refresh Token 쿠키를 주고받으려면 필요하다.
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> sendError(response, ErrorCode.AUTH_REQUIRED);
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> sendError(response, ErrorCode.FORBIDDEN);
    }

    // 필터 단계 오류는 GlobalExceptionHandler를 타지 않으므로 여기서 같은 형식으로 직접 쓴다.
    private void sendError(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getStatus().value());
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.fail(errorCode, errorCode.getDefaultMessage()));
    }
}
