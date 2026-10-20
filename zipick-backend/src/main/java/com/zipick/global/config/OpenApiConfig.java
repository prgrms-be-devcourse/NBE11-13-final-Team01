package com.zipick.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI 의 JWT 인증 설정.
 *
 * 이 설정이 없으면 Swagger UI 에 Authorize 버튼이 생기지 않아,
 * 로그인해서 Access Token 을 받아도 붙일 자리가 없어 보호된 API 를 전혀 테스트할 수 없다.
 *
 * 기본값을 '인증 필요'로 두고, 비로그인으로 쓸 수 있는 컨트롤러에만
 * {@code @SecurityRequirements} 로 해제한다. SecurityConfig 의 permitAll 목록과 짝을 맞춘다.
 * (새 API 를 추가하면 기본이 '인증 필요'라, 빠뜨려도 문서가 덜 위험한 쪽으로 틀린다)
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    OpenAPI zipickOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ZIPICK API")
                        .version("v1")
                        .description("공공임대 공급주택을 지도에서 탐색하고 조건에 맞는 집을 추천하는 서비스"))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, bearerScheme()))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }

    private SecurityScheme bearerScheme() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("""
                        로그인(POST /api/auth/login) 응답의 accessToken 값을 넣는다. 'Bearer ' 는 붙이지 않는다.

                        Refresh Token 은 HttpOnly 쿠키(refreshToken)로 오가므로 여기에 넣지 않는다.
                        만료되면 POST /api/auth/reissue 로 새 Access Token 을 받아 다시 넣는다.
                        """);
    }
}
