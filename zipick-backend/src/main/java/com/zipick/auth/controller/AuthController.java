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

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final TokenService tokenService;
    private final CookieUtil cookieUtil;
    private final JwtProperties jwtProperties;

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ApiResponse.ok(authService.signup(request));
    }

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
