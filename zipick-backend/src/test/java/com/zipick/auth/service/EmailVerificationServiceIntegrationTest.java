package com.zipick.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zipick.auth.config.EmailVerificationProperties;
import com.zipick.auth.entity.EmailVerification;
import com.zipick.auth.repository.EmailVerificationRepository;
import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.notification.client.NotificationClient;
import com.zipick.support.PostgresRepositoryTestBase;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 틀린 횟수가 실제 DB 에 남는지. 단위 테스트(목)로는 트랜잭션 롤백을 볼 수 없어
 * "예외와 함께 횟수 증가분이 롤백되어 5회 제한이 동작하지 않던" 버그를 놓쳤다.
 *
 * <p>테스트 트랜잭션을 끄고(NOT_SUPPORTED) 서비스 호출마다 자기 트랜잭션으로 커밋·롤백되게 한다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({EmailVerificationService.class, EmailVerificationServiceIntegrationTest.Config.class})
class EmailVerificationServiceIntegrationTest extends PostgresRepositoryTestBase {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T03:00:00Z"), SEOUL);
    static final String EMAIL = "attempts@zipick.test";
    static final String CODE = "123456";

    @TestConfiguration
    static class Config {
        @Bean Clock clock() { return CLOCK; }
        @Bean EmailVerificationProperties emailVerificationProperties() {
            return new EmailVerificationProperties(true, null, null, null, 5, 10);
        }
    }

    @MockitoBean NotificationClient notifications;
    @Autowired EmailVerificationService service;
    @Autowired EmailVerificationRepository verifications;

    @AfterEach
    void clean() { verifications.deleteAll(); }

    private void issueCode() {
        LocalDateTime now = LocalDateTime.now(CLOCK);
        verifications.save(EmailVerification.issue(EMAIL, EmailVerificationService.hash(EMAIL, CODE), now, now.plusMinutes(10)));
    }

    private int storedAttempts() { return verifications.findByEmail(EMAIL).orElseThrow().getAttempts(); }

    @Test
    void wrongCodeAttemptsAreCommittedEvenThoughTheRequestFails() {
        issueCode();
        for (int i = 1; i <= 4; i++) {
            assertThatThrownBy(() -> service.confirm(EMAIL, "000000"))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.VERIFICATION_CODE_INVALID);
            assertThat(storedAttempts()).isEqualTo(i);
        }
        assertThatThrownBy(() -> service.confirm(EMAIL, "000000"))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VERIFICATION_TOO_MANY_ATTEMPTS);
        assertThat(storedAttempts()).isEqualTo(5);
    }

    @Test
    void afterTooManyAttemptsEvenTheRightCodeIsRejected() {
        issueCode();
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.confirm(EMAIL, "000000")).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> service.confirm(EMAIL, CODE))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VERIFICATION_TOO_MANY_ATTEMPTS);
        assertThat(verifications.findByEmail(EMAIL).orElseThrow().getVerifiedAt()).isNull();
    }

    @Test
    void rightCodeIssuesTokenAndIsStored() {
        issueCode();
        String token = service.confirm(EMAIL, CODE).verificationToken();
        assertThat(token).isNotBlank();
        service.assertVerified(EMAIL, token);
    }
}
