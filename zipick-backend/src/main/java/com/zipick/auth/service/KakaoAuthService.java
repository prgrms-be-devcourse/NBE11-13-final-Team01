package com.zipick.auth.service;

import com.zipick.auth.oauth.KakaoOAuthClient;
import com.zipick.auth.oauth.KakaoOAuthProperties;
import com.zipick.auth.oauth.dto.KakaoUserResponse;
import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.user.entity.AuthProvider;
import com.zipick.user.entity.User;
import com.zipick.user.repository.UserRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 카카오 로그인.
 *
 * 인가 코드를 카카오 토큰으로 바꾸고 사용자 정보를 읽은 뒤,
 * 그 뒤로는 기존 TokenService 를 그대로 써서 ZIPICK 자체 JWT 를 발급한다.
 * 즉 토큰 정책(Access 1시간 / Refresh 7일 / Rotation / 사용자당 1개)은 이메일 로그인과 완전히 같다.
 *
 * 카카오 Access Token 은 사용자 조회 1회에만 쓰고 저장하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KakaoAuthService {

    private final KakaoOAuthClient kakaoOAuthClient;
    private final KakaoOAuthProperties properties;
    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final KakaoUserRegistrar registrar;

    public String authorizeUrl(String state) {
        assertConfigured();
        return kakaoOAuthClient.authorizeUrl(state);
    }

    /**
     * 여기에는 @Transactional 을 걸지 않는다.
     *
     * <p>이 메서드는 카카오 서버를 두 번 호출한다(토큰 교환·사용자 조회, 각각 최대 5초).
     * 트랜잭션으로 묶으면 그동안 DB 커넥션을 쥐고 있게 돼, 카카오가 느려지면 커넥션 풀이 먼저 마른다.
     *
     * <p>계정 저장은 {@link KakaoUserRegistrar}, Refresh Token 저장은 {@link TokenService#issueToken}
     * 이 각자 자기 트랜잭션에서 처리한다. 둘을 한 트랜잭션으로 묶지 않아도 되는 이유는,
     * 계정만 만들어지고 토큰 발급이 실패해도 다음 로그인에서 providerId 로 그 계정을 찾아
     * 토큰만 다시 발급하면 되기 때문이다(고아 데이터가 아니다).
     */
    public TokenService.TokenPair login(String code) {

        assertConfigured();

        String kakaoAccessToken = kakaoOAuthClient.exchangeToken(code);
        KakaoUserResponse kakaoUser = kakaoOAuthClient.fetchUser(kakaoAccessToken);

        User user = findOrCreate(kakaoUser);

        log.info("[AUTH] 카카오 로그인 성공 userId: {}", user.getId());

        return tokenService.issueToken(user);
    }

    private User findOrCreate(KakaoUserResponse kakaoUser) {

        String providerId = kakaoUser.providerId();

        // 1. 재로그인. 이메일 비교보다 항상 우선한다 (카카오에서 이메일을 바꿔도 같은 계정이어야 한다).
        return userRepository.findByProviderAndProviderId(AuthProvider.KAKAO, providerId)
                .orElseGet(() -> createOrReuse(providerId, kakaoUser.verifiedEmail()));
    }

    /**
     * 신규 가입. 같은 계정으로 동시에 처음 로그인한 경우까지 한 계정으로 모은다.
     *
     * <p>저장은 {@link KakaoUserRegistrar} 가 <b>자기 트랜잭션</b>에서 한다.
     * 그래서 제약 위반 예외가 여기로 올라올 때는 안쪽 트랜잭션이 이미 롤백돼 끝나 있고,
     * 이 메서드의 연결은 멀쩡해 다시 조회할 수 있다(이유는 KakaoUserRegistrar javadoc 참고).
     */
    private User createOrReuse(String providerId, String verifiedEmail) {

        try {
            return registrar.register(providerId, verifiedEmail);

        } catch (DataIntegrityViolationException e) {

            // uk_users_provider 에 걸린 경우. 먼저 저장된 행을 그대로 쓴다 → 두 요청이 같은 userId 를 받는다.
            Optional<User> winner = userRepository.findByProviderAndProviderId(AuthProvider.KAKAO, providerId);

            if (winner.isPresent()) {
                log.info("[AUTH] 카카오 계정 동시 가입 감지. 먼저 저장된 계정을 사용합니다 userId: {}", winner.get().getId());
                return winner.get();
            }

            // providerId 행이 없는데 제약에 걸렸다면 남은 유일 제약은 uk_users_email 뿐이다.
            // 이메일 중복 검사와 INSERT 사이에 다른 요청이 그 이메일로 먼저 가입한 경우다.
            // 계정을 잘못 연결하지 않도록 여기서도 똑같이 "기존 로그인 수단을 쓰라"고 안내한다.
            if (verifiedEmail != null) {
                log.info("[AUTH] 카카오 이메일이 가입 직전에 선점됐습니다");
                throw new BusinessException(ErrorCode.SOCIAL_EMAIL_ALREADY_REGISTERED);
            }

            throw e;
        }
    }

    private void assertConfigured() {
        if (!properties.isConfigured()) {
            log.warn("[OAUTH] 카카오 로그인 설정(kakao.oauth.client-id / redirect-uri)이 없습니다");
            throw new BusinessException(ErrorCode.OAUTH_NOT_CONFIGURED);
        }
    }
}
