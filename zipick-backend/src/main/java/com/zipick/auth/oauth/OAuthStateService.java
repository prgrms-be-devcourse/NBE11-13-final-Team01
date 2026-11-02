package com.zipick.auth.oauth;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * OAuth state(CSRF 방지 난수) 생성·검증.
 *
 * 값은 서버가 만들어 HttpOnly 쿠키에 넣는다. 프론트가 만들면 공격자도 같은 값을 만들 수 있어
 * 검증의 의미가 없어진다. 콜백에서 "쿠키의 값"과 "카카오가 돌려준 값"이 같은지 확인하므로,
 * 공격자가 자기 인가 코드를 피해자 브라우저로 밀어 넣는 로그인 CSRF 를 막는다.
 *
 * 쿠키는 검증 성공·실패와 무관하게 한 번 쓰면 지운다(재사용 방지). 컨트롤러가 삭제를 담당한다.
 */
@Slf4j
@Component
public class OAuthStateService {

    public static final String STATE_COOKIE = "kakaoOauthState";

    private static final int STATE_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        byte[] bytes = new byte[STATE_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 쿠키 값과 요청 값이 같은지 확인한다. 길이로 정답을 추측하지 못하도록 상수 시간 비교를 쓴다. */
    public void verify(String cookieState, String requestState) {

        if (cookieState == null || cookieState.isBlank() || requestState == null || requestState.isBlank()) {
            log.warn("[OAUTH] state 누락 (쿠키 만료 또는 직접 호출)");
            throw new BusinessException(ErrorCode.OAUTH_STATE_INVALID);
        }

        boolean matches = MessageDigest.isEqual(
                cookieState.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                requestState.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        if (!matches) {
            log.warn("[OAUTH] state 불일치");
            throw new BusinessException(ErrorCode.OAUTH_STATE_INVALID);
        }
    }
}
