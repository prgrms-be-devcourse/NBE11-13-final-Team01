package com.zipick.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.zipick.auth.oauth.KakaoOAuthClient;
import com.zipick.auth.oauth.KakaoOAuthProperties;
import com.zipick.auth.oauth.dto.KakaoUserResponse;
import com.zipick.auth.oauth.dto.KakaoUserResponse.KakaoAccount;
import com.zipick.support.PostgresRepositoryTestBase;
import com.zipick.user.entity.AuthProvider;
import com.zipick.user.entity.User;
import com.zipick.user.repository.UserRepository;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 같은 카카오 계정의 동시 최초 로그인을 <b>실제 PostgreSQL</b> 에서 확인한다.
 *
 * <p>Mock 으로 {@code DataIntegrityViolationException} 을 던지는 단위 테스트는
 * "예외가 나면 이렇게 처리한다"까지만 보여 준다. 정작 중요한
 * <b>"제약 위반 뒤에도 그 트랜잭션에서 질의를 더 할 수 있는가"</b> 는 진짜 DB 에서만 드러난다.
 * PostgreSQL 은 할 수 없다. 그래서 저장을 별도 트랜잭션(REQUIRES_NEW)으로 분리했고,
 * 이 테스트가 그 분리가 실제로 필요하고 실제로 동작한다는 것을 양쪽에서 고정한다.
 *
 * <p>Docker 가 없으면 전체가 건너뛴다. 건너뛰면 이 검증은 이뤄지지 않은 것이다.
 */
@Import(KakaoUserRegistrar.class)
class KakaoSignupConcurrencyIntegrationTest extends PostgresRepositoryTestBase {

    private static final String PROVIDER_ID = "4071234567";
    private static final String CODE = "kakao-authorization-code";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private KakaoUserRegistrar registrar;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private KakaoAuthService kakaoAuthService;

    @BeforeEach
    void setUp() {
        // 카카오 호출과 토큰 발급만 대역으로 두고, 계정 조회·생성 경로는 실제 코드를 그대로 쓴다.
        KakaoOAuthClient client = mock(KakaoOAuthClient.class);
        given(client.exchangeToken(CODE)).willReturn("kakao-access-token");
        given(client.fetchUser(any()))
                .willReturn(new KakaoUserResponse(Long.valueOf(PROVIDER_ID), new KakaoAccount(null, true, true)));

        TokenService tokenService = mock(TokenService.class);
        given(tokenService.issueToken(any(User.class)))
                .willReturn(new TokenService.TokenPair("access", "refresh"));

        kakaoAuthService = new KakaoAuthService(
                client,
                new KakaoOAuthProperties("client-id", "client-secret",
                        "http://localhost:5173/oauth/kakao/callback", null, null, Duration.ofMinutes(5)),
                userRepository,
                tokenService,
                registrar);
    }

    /** 테스트의 바깥 트랜잭션은 롤백되지만 아래 스레드들이 커밋한 행은 남으므로 직접 지운다. */
    @AfterEach
    void tearDown() {
        newTransaction().executeWithoutResult(status ->
                userRepository.findByProviderAndProviderId(AuthProvider.KAKAO, PROVIDER_ID)
                        .ifPresent(userRepository::delete));
    }

    /** 이 카카오 계정으로 실제 저장된 행 수. 커밋된 상태를 봐야 하므로 새 트랜잭션에서 센다. */
    private long savedAccountCount() {
        Long count = newTransaction().execute(status -> userRepository.findAll().stream()
                .filter(user -> PROVIDER_ID.equals(user.getProviderId()))
                .count());
        return count == null ? 0L : count;
    }

    private Long savedAccountId() {
        return newTransaction().execute(status ->
                userRepository.findByProviderAndProviderId(AuthProvider.KAKAO, PROVIDER_ID).orElseThrow().getId());
    }

    private TransactionTemplate newTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    @Test
    @DisplayName("같은 카카오 계정으로 동시에 처음 로그인해도 계정은 하나만 만들어지고 둘 다 같은 userId 를 받는다")
    void concurrentFirstLoginConvergesOnOneAccount() throws Exception {
        // 두 요청이 최대한 같은 순간에 '없음' 조회를 통과하도록 출발선을 맞춘다.
        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Callable<Long> login = () -> {
            startLine.await(5, TimeUnit.SECONDS);
            kakaoAuthService.login(CODE);
            return userRepository.findByProviderAndProviderId(AuthProvider.KAKAO, PROVIDER_ID)
                    .orElseThrow().getId();
        };

        try {
            List<Future<Long>> results = pool.invokeAll(List.of(login, login));

            Long first = results.get(0).get(20, TimeUnit.SECONDS);
            Long second = results.get(1).get(20, TimeUnit.SECONDS);

            // 둘 다 성공하고 같은 계정을 가리켜야 한다. 한쪽이라도 실패하면 위 get()에서 터진다.
            assertThat(first).isNotNull().isEqualTo(second);

            // 행도 정확히 하나여야 한다.
            assertThat(savedAccountCount()).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("[회귀 방지] 제약 위반이 난 트랜잭션에서는 재조회가 불가능하다 — 저장을 별도 트랜잭션으로 분리한 이유")
    void sameTransactionCannotRecoverAfterConstraintViolation() {
        // 이긴 요청이 이미 커밋해 둔 상태
        newTransaction().executeWithoutResult(status ->
                userRepository.saveAndFlush(User.ofKakao(PROVIDER_ID, null)));

        // 진 요청이 "같은 트랜잭션 안에서" 잡아 재조회하는, 분리 이전의 구조를 그대로 재현한다.
        assertThatThrownBy(() -> newTransaction().execute(status -> {
            try {
                return userRepository.saveAndFlush(User.ofKakao(PROVIDER_ID, null));
            } catch (DataIntegrityViolationException e) {
                // PostgreSQL 은 여기서 25P02 로 거부하고, JPA 는 이미 rollback-only 로 표시해 둔다.
                return userRepository.findByProviderAndProviderId(AuthProvider.KAKAO, PROVIDER_ID)
                        .orElseThrow(() -> e);
            }
        }))
                // 복구에 성공해 사용자를 돌려주는 일은 일어나지 않는다.
                // 실제 관측값은 org.hibernate.AssertionFailure 다(flush 가 깨진 세션을 더 쓸 수 없다).
                // 구체 타입은 Hibernate 버전에 따라 달라질 수 있어 "예외로 끝난다"까지만 고정한다.
                .isInstanceOf(RuntimeException.class);

        // 행은 여전히 하나뿐이다 (진 요청은 아무것도 남기지 못한다).
        assertThat(savedAccountCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("분리한 뒤에는 제약 위반 뒤에도 먼저 저장된 계정을 다시 읽어 쓸 수 있다")
    void registrarIsolationAllowsRecovery() {
        Long winnerId = newTransaction().execute(status ->
                userRepository.saveAndFlush(User.ofKakao(PROVIDER_ID, null)).getId());

        // 이미 있는 계정이므로 login 은 조회로 끝나야 하고, 중복 생성도 예외도 없어야 한다.
        kakaoAuthService.login(CODE);

        assertThat(savedAccountId()).isEqualTo(winnerId);
        assertThat(savedAccountCount()).isEqualTo(1L);
    }
}
