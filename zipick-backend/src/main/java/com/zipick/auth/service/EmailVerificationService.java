package com.zipick.auth.service;

import com.zipick.auth.config.EmailVerificationProperties;
import com.zipick.auth.dto.response.EmailVerificationConfirmResponse;
import com.zipick.auth.dto.response.EmailVerificationSendResponse;
import com.zipick.auth.entity.EmailVerification;
import com.zipick.auth.repository.EmailVerificationRepository;
import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.notification.client.NotificationClient;
import com.zipick.notification.client.NotificationRequest;
import com.zipick.notification.dto.NotificationEventResponse;
import com.zipick.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원가입 이메일 인증: 코드 보내기 → 코드 확인(인증 완료 토큰 발급) → 가입 때 토큰 확인 → 가입 뒤 지우기.
 *
 * 메일은 알림센터에 AUTH_EMAIL_VERIFY 템플릿으로 요청한다 (docs/notification-api.md). 사용자가 결과를 바로 알아야 하므로
 * 큐가 아니라 HTTP delivery=now 로 보내고, 실패하면 그 자리에서 알린다.
 * "보내도 되는지"(이미 가입한 이메일, 재전송 간격, 하루 횟수)와 코드 검증은 여기서 한다 — 알림센터는 판단하지 않는다.
 *
 * 안전장치: SecureRandom 코드, 코드·토큰은 해시로만 저장, 상수 시간 비교, 틀린 횟수 상한, 재발송 간격·하루 상한,
 * 새 코드를 보내면 이전 코드 무효, 토큰은 가입 한 번에 폐기.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailVerificationService {

    public static final String TEMPLATE = "AUTH_EMAIL_VERIFY";

    private final EmailVerificationRepository verifications;
    private final UserRepository users;
    private final NotificationClient notifications;
    private final EmailVerificationProperties props;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /** 코드를 만들어 보낸다. 같은 이메일로 다시 부르면 새 코드로 바뀌고 이전 코드는 무효 */
    @Transactional
    public EmailVerificationSendResponse send(String rawEmail) {
        String email = normalize(rawEmail);
        int ttlMin = (int) props.codeTtl().toMinutes();
        int cooldownSec = (int) props.resendCooldown().toSeconds();
        if (!props.required()) return new EmailVerificationSendResponse(false, ttlMin, cooldownSec);
        if (users.existsByEmail(email)) throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);

        LocalDateTime now = LocalDateTime.now(clock);
        EmailVerification v = verifications.findByEmail(email).orElse(null);
        if (v != null) {
            if (Duration.between(v.getSentAt(), now).compareTo(props.resendCooldown()) < 0) {
                throw new BusinessException(ErrorCode.VERIFICATION_RESEND_TOO_SOON);
            }
            if (v.sendsToday(now.toLocalDate()) >= props.maxSendsPerDay()) {
                throw new BusinessException(ErrorCode.VERIFICATION_DAILY_LIMIT);
            }
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        LocalDateTime expiresAt = now.plus(props.codeTtl());
        if (v == null) v = verifications.save(EmailVerification.issue(email, hash(email, code), now, expiresAt));
        else v.reissue(hash(email, code), now, expiresAt);

        // 결과를 바로 받아야 하므로 HTTP(delivery=now). 알림센터·메일 장애는 NOTIFICATION_UNAVAILABLE 로 사용자에게 알린다
        NotificationEventResponse sent = notifications.enqueue(NotificationRequest.toEmail(TEMPLATE, email,
                Map.of("code", code, "expiresInMinutes", ttlMin)).now());
        if (!"SENT".equals(sent.status())) {
            log.warn("인증 메일 발송 실패: email={} status={} deliveries={}", mask(email), sent.status(), sent.deliveries());
            throw new BusinessException(ErrorCode.NOTIFICATION_UNAVAILABLE, "인증 메일을 보내지 못했습니다. 잠시 뒤 다시 시도해 주세요.");
        }
        log.info("[AUTH] 인증 메일 발송 email={} todayCount={}", mask(email), v.sendsToday(now.toLocalDate()));
        return new EmailVerificationSendResponse(true, ttlMin, cooldownSec);
    }

    /** 코드를 확인하고 인증 완료 토큰을 발급한다. 틀리면 횟수를 세고, 상한을 넘으면 다시 받아야 한다 */
    @Transactional
    public EmailVerificationConfirmResponse confirm(String rawEmail, String code) {
        String email = normalize(rawEmail);
        LocalDateTime now = LocalDateTime.now(clock);
        EmailVerification v = verifications.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_CODE_INVALID));
        if (v.isExpired(now)) throw new BusinessException(ErrorCode.VERIFICATION_CODE_INVALID);
        if (v.getAttempts() >= props.maxAttempts()) throw new BusinessException(ErrorCode.VERIFICATION_TOO_MANY_ATTEMPTS);
        if (!MessageDigest.isEqual(v.getCodeHash().getBytes(StandardCharsets.UTF_8), hash(email, code).getBytes(StandardCharsets.UTF_8))) {
            v.recordFailure();
            if (v.getAttempts() >= props.maxAttempts()) throw new BusinessException(ErrorCode.VERIFICATION_TOO_MANY_ATTEMPTS);
            throw new BusinessException(ErrorCode.VERIFICATION_CODE_INVALID);
        }
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        v.markVerified(hash(email, token), now, now.plus(props.tokenTtl()));
        log.info("[AUTH] 이메일 인증 완료 email={}", mask(email));
        return new EmailVerificationConfirmResponse(token, (int) props.tokenTtl().toMinutes());
    }

    /** 가입 직전: 그 이메일의 인증 완료 토큰이 맞고 아직 유효한지. required=false 면 통과 */
    @Transactional(readOnly = true)
    public void assertVerified(String rawEmail, String token) {
        if (!props.required()) return;
        String email = normalize(rawEmail);
        if (token == null || token.isBlank()) throw new BusinessException(ErrorCode.EMAIL_NOT_VERIFIED);
        LocalDateTime now = LocalDateTime.now(clock);
        boolean ok = verifications.findByEmail(email)
                .filter(v -> v.tokenValid(hash(email, token), now))
                .isPresent();
        if (!ok) throw new BusinessException(ErrorCode.EMAIL_NOT_VERIFIED);
    }

    /** 가입이 끝나면 인증 기록(토큰 포함)을 지운다 — 토큰은 한 번만 쓸 수 있다 */
    @Transactional
    public void consume(String rawEmail) {
        verifications.deleteByEmail(normalize(rawEmail));
    }

    /** 만료된 지 하루가 지난 인증 행 정리. 매일 04:10 */
    @Scheduled(cron = "0 10 4 * * *", zone = "Asia/Seoul")
    @Transactional
    public void cleanupExpired() {
        long removed = verifications.deleteByExpiresAtBefore(LocalDateTime.now(clock).minusDays(1));
        if (removed > 0) log.info("[AUTH] 만료된 이메일 인증 {}건 정리", removed);
    }

    static String normalize(String email) { return email.trim().toLowerCase(); }

    /** 코드·토큰은 해시로만 저장. 이메일을 섞어 같은 값이라도 해시가 다르게 */
    static String hash(String email, String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest((email + ":" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String mask(String email) {
        int at = email.indexOf('@');
        return at <= 1 ? "***" + email.substring(Math.max(at, 0)) : email.charAt(0) + "***" + email.substring(at);
    }
}
