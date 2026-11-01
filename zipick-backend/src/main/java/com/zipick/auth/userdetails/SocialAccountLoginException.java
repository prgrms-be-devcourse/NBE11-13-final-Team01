package com.zipick.auth.userdetails;

import com.zipick.user.entity.AuthProvider;
import lombok.Getter;
import org.springframework.security.core.AuthenticationException;

/**
 * 소셜 계정으로 비밀번호 로그인을 시도했을 때.
 *
 * UsernameNotFoundException 과 달리 DaoAuthenticationProvider 가 가로채지 않아
 * AuthService 까지 그대로 전달된다. 비밀번호 비교 단계에 들어가기 전에 끊으므로
 * "비밀번호가 null 이라 그냥 실패"하는 것과 달리 원인을 구분해 안내할 수 있다.
 */
@Getter
public class SocialAccountLoginException extends AuthenticationException {

    private final AuthProvider provider;

    public SocialAccountLoginException(AuthProvider provider) {
        super("소셜 계정은 비밀번호 로그인을 사용할 수 없습니다.");
        this.provider = provider;
    }
}
