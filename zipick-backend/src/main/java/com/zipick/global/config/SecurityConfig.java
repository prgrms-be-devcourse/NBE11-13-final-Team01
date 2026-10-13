package com.zipick.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * TODO(인증 담당): 카카오 로그인·JWT 필터가 붙으면 이 설정을 교체한다.
 *
 * 지금은 인증 없이 모든 API를 열어 두고, 사용자는 X-User-Id 헤더(CurrentUserArgumentResolver)로 구분한다.
 * spring-boot-starter-security 만 있고 설정이 없으면 모든 요청이 막혀서 임시로 둔다.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .build();
    }
}
