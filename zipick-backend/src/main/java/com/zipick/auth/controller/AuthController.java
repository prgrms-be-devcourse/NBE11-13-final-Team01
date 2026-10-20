package com.zipick.auth.controller;

import com.zipick.auth.dto.request.LoginRequest;
import com.zipick.auth.dto.request.SignupRequest;
import com.zipick.auth.dto.response.LoginResponse;
import com.zipick.auth.dto.response.SignupResponse;
import com.zipick.auth.dto.response.TokenResponse;
import com.zipick.auth.jwt.JwtProperties;
import com.zipick.auth.service.AuthService;
import com.zipick.auth.userdetails.CustomUserDetails;
import com.zipick.auth.service.TokenService;
import com.zipick.global.response.ApiResponse;
import com.zipick.global.util.CookieUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "인증", description = "이메일·비밀번호 회원가입과 로그인. "
        + "Access Token은 응답 바디로, Refresh Token은 HttpOnly 쿠키(refreshToken)로 오간다.")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final TokenService tokenService;
    private final CookieUtil cookieUtil;
    private final JwtProperties jwtProperties;

    @Operation(summary = "회원가입",
            description = "이메일과 비밀번호로 가입한다. 이미 쓰는 이메일이면 409 DUPLICATE_EMAIL.")
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ApiResponse.ok(authService.signup(request));
    }

    @Operation(summary = "로그인",
            description = "Access Token(1시간)은 응답 바디로 돌려주고, "
                    + "Refresh Token(7일)은 HttpOnly 쿠키로 내려준다. "
                    + "Access Token은 브라우저 저장소에 두지 말고 메모리에만 보관한다.")
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response
    ) {

        TokenService.TokenPair tokenPair = authService.login(request);

        // Refresh Token은 HttpOnly 쿠키로, Access Token은 응답 바디로 내려준다.
        cookieUtil.addCookie(
                response,
                CookieUtil.REFRESH_TOKEN_COOKIE,
                tokenPair.refreshToken(),
                jwtProperties.refreshTokenExpiration()
        );

        return ApiResponse.ok(new LoginResponse(tokenPair.accessToken()));
    }

    @Operation(summary = "Access Token 재발급",
            description = "요청 바디 없이 refreshToken 쿠키만으로 동작한다. "
                    + "Rotation이라 호출할 때마다 Refresh Token 쿠키도 새 값으로 교체되므로, "
                    + "같은 쿠키로 두 번 호출하면 두 번째는 실패한다. "
                    + "프론트는 앱 시작 시 1회 호출해 로그인 상태를 복구한다.")
    @PostMapping("/reissue")
    public ApiResponse<TokenResponse> reissue(HttpServletRequest request, HttpServletResponse response) {

        String refreshToken = cookieUtil.getCookieValue(request, CookieUtil.REFRESH_TOKEN_COOKIE);

        TokenService.TokenPair tokenPair = tokenService.reissue(refreshToken);

        // Rotation이므로 새 Refresh Token으로 쿠키도 교체한다.
        cookieUtil.addCookie(
                response,
                CookieUtil.REFRESH_TOKEN_COOKIE,
                tokenPair.refreshToken(),
                jwtProperties.refreshTokenExpiration()
        );

        return ApiResponse.ok(new TokenResponse(tokenPair.accessToken()));
    }

    @Operation(summary = "로그아웃",
            description = "저장된 Refresh Token을 지우고 쿠키도 만료시킨다. 응답은 204. "
                    + "Access Token이 필요한 보호 API다.")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            HttpServletResponse response
    ) {

        tokenService.logout(userDetails.getUser().getId());

        cookieUtil.deleteCookie(response, CookieUtil.REFRESH_TOKEN_COOKIE);
    }
}
