package com.zipick.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.zipick.global.config.CookieProperties;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CookieUtilTest {

    @Nested
    @DisplayName("로컬 설정 (secure=false)")
    class LocalProfile {

        private final CookieUtil cookieUtil = new CookieUtil(new CookieProperties(false, "Lax"));

        @Test
        @DisplayName("HttpOnly와 SameSite가 붙고 secure는 빠진다")
        void addCookie() {
            MockHttpServletResponse response = new MockHttpServletResponse();

            cookieUtil.addCookie(response, CookieUtil.REFRESH_TOKEN_COOKIE, "token-value", Duration.ofDays(7));

            String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
            assertThat(setCookie).contains("refreshToken=token-value");
            assertThat(setCookie).contains("HttpOnly");
            assertThat(setCookie).contains("SameSite=Lax");
            assertThat(setCookie).contains("Path=/");
            assertThat(setCookie).contains("Max-Age=604800");
            assertThat(setCookie).doesNotContain("Secure");
        }
    }

    @Nested
    @DisplayName("운영 설정 (secure=true)")
    class ProdProfile {

        private final CookieUtil cookieUtil = new CookieUtil(new CookieProperties(true, "None"));

        @Test
        @DisplayName("Secure와 지정한 SameSite가 붙는다")
        void addCookie() {
            MockHttpServletResponse response = new MockHttpServletResponse();

            cookieUtil.addCookie(response, CookieUtil.REFRESH_TOKEN_COOKIE, "token-value", Duration.ofDays(7));

            String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
            assertThat(setCookie).contains("Secure");
            assertThat(setCookie).contains("SameSite=None");
        }
    }

    @Nested
    @DisplayName("삭제와 조회")
    class DeleteAndRead {

        private final CookieUtil cookieUtil = new CookieUtil(new CookieProperties(false, "Lax"));

        @Test
        @DisplayName("삭제하면 값이 비고 Max-Age가 0이 된다")
        void deleteCookie() {
            MockHttpServletResponse response = new MockHttpServletResponse();

            cookieUtil.deleteCookie(response, CookieUtil.REFRESH_TOKEN_COOKIE);

            String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
            assertThat(setCookie).contains("Max-Age=0");
            assertThat(setCookie).contains("refreshToken=;");
        }

        @Test
        @DisplayName("요청에서 쿠키 값을 꺼낸다")
        void getCookieValue() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setCookies(new Cookie(CookieUtil.REFRESH_TOKEN_COOKIE, "token-value"));

            assertThat(cookieUtil.getCookieValue(request, CookieUtil.REFRESH_TOKEN_COOKIE))
                    .isEqualTo("token-value");
        }

        @Test
        @DisplayName("쿠키가 없으면 null을 반환한다")
        void noCookie() {
            MockHttpServletRequest request = new MockHttpServletRequest();

            assertThat(cookieUtil.getCookieValue(request, CookieUtil.REFRESH_TOKEN_COOKIE)).isNull();
        }

        @Test
        @DisplayName("찾는 이름의 쿠키가 없으면 null을 반환한다")
        void otherCookieOnly() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setCookies(new Cookie("other", "value"));

            assertThat(cookieUtil.getCookieValue(request, CookieUtil.REFRESH_TOKEN_COOKIE)).isNull();
        }
    }
}
