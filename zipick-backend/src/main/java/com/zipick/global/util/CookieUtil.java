package com.zipick.global.util;

import com.zipick.global.config.CookieProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

// SameSite 속성을 지정하려면 서블릿 Cookie 대신 Spring의 ResponseCookie가 필요하다.
@Component
@RequiredArgsConstructor
public class CookieUtil {

    public static final String REFRESH_TOKEN_COOKIE = "refreshToken";

    private final CookieProperties cookieProperties;

    public void addCookie(HttpServletResponse response, String name, String value, Duration maxAge) {

        ResponseCookie cookie = ResponseCookie.from(name, value)
                // JS에서 읽지 못하게 해 XSS로 토큰이 탈취되는 것을 막는다.
                .httpOnly(true)
                .secure(cookieProperties.secure())
                .sameSite(cookieProperties.sameSite())
                .path("/")
                .maxAge(maxAge)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    // 만료 시간을 0으로 덮어써 브라우저가 쿠키를 지우게 한다.
    public void deleteCookie(HttpServletResponse response, String name) {
        addCookie(response, name, "", Duration.ZERO);
    }

    public String getCookieValue(HttpServletRequest request, String name) {

        Cookie[] cookies = request.getCookies();

        if (cookies == null) {
            return null;
        }

        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }

        return null;
    }
}
