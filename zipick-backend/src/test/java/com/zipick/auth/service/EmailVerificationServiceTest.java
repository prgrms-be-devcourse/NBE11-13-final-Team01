package com.zipick.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zipick.auth.config.EmailVerificationProperties;
import com.zipick.auth.entity.EmailVerification;
import com.zipick.auth.repository.EmailVerificationRepository;
import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.notification.client.NotificationClient;
import com.zipick.notification.client.NotificationRequest;
import com.zipick.notification.dto.NotificationEventResponse;
import com.zipick.user.repository.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 가입 이메일 인증: "보내도 되는지"(가입 여부, 재전송 간격)와 코드 검증은 메인 서버가, 발송은 알림센터가 */
@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant T0 = Instant.parse("2026-10-27T01:00:00Z");

    @Mock EmailVerificationRepository verifications;
    @Mock UserRepository users;
    @Mock NotificationClient notifications;
    MutableClock clock = new MutableClock(T0);
    EmailVerificationService service;

    /** 테스트에서 시간을 옮길 수 있는 Clock */
    static class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        void plus(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return SEOUL; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach
    void setUp() {
        service = new EmailVerificationService(verifications, users, notifications,
                new EmailVerificationProperties(true, Duration.ofMinutes(10), Duration.ofMinutes(10), Duration.ofSeconds(60), 3, 3), clock);
    }

    private static NotificationEventResponse sent(String status) {
        return new NotificationEventResponse(1L, "main", "AUTH_EMAIL_VERIFY", null, List.of("email"), "t", status, List.of(), List.of(), Map.of(),
                LocalDateTime.now(), LocalDateTime.now(), false);
    }

    /** send() 가 저장한 행, 메일로 보낸 코드, 알림센터 요청을 같이 돌려준다 */
    private record Issued(EmailVerification row, String code, NotificationRequest request) {}

    private Issued sendFresh(String email) {
        String normalized = email.trim().toLowerCase();
        when(verifications.findByEmail(normalized)).thenReturn(Optional.empty());
        when(verifications.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(notifications.enqueue(any())).thenReturn(sent("SENT"));
        service.send(email);
        ArgumentCaptor<EmailVerification> row = ArgumentCaptor.forClass(EmailVerification.class);
        verify(verifications).save(row.capture());
        ArgumentCaptor<NotificationRequest> req = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications).enqueue(req.capture());
        return new Issued(row.getValue(), (String) req.getValue().variables().get("code"), req.getValue());
    }

    @Test
    void 코드를_보내면_해시만_저장하고_알림센터에_now_로_요청한다() {
        Issued i = sendFresh("New@Example.com");
        NotificationRequest req = i.request();

        assertThat(i.code()).matches("\\d{6}");
        assertThat(i.row().getEmail()).isEqualTo("new@example.com");            // 소문자로 정규화
        assertThat(i.row().getCodeHash()).hasSize(64).doesNotContain(i.code()); // 평문 코드는 저장하지 않음
        assertThat(req.templateCode()).isEqualTo("AUTH_EMAIL_VERIFY");
        assertThat(req.channels()).containsExactly("email");
        assertThat(req.to().email()).isEqualTo("new@example.com");
        assertThat(req.delivery()).isEqualTo("now");
        assertThat(req.variables()).containsEntry("expiresInMinutes", 10);
    }

    @Test
    void 인증이_꺼진_서버는_보내지_않고_required_false() {
        service = new EmailVerificationService(verifications, users, notifications,
                new EmailVerificationProperties(false, null, null, null, 0, 0), clock);
        assertThat(service.send("a@b.co").required()).isFalse();
        assertThatCode(() -> service.assertVerified("a@b.co", null)).doesNotThrowAnyException();
        verify(notifications, never()).enqueue(any());
    }

    @Test
    void 이미_가입한_이메일이면_보내지_않는다() {
        when(users.existsByEmail("a@b.co")).thenReturn(true);
        assertThatThrownBy(() -> service.send("a@b.co")).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_EMAIL);
        verify(notifications, never()).enqueue(any());
    }

    @Test
    void 재전송_간격_안이면_거부하고_지나면_새_코드로_바꾼다() {
        Issued first = sendFresh("a@b.co");
        when(verifications.findByEmail("a@b.co")).thenReturn(Optional.of(first.row()));

        clock.plus(Duration.ofSeconds(30));
        assertThatThrownBy(() -> service.send("a@b.co")).extracting("errorCode").isEqualTo(ErrorCode.VERIFICATION_RESEND_TOO_SOON);

        clock.plus(Duration.ofSeconds(31));
        String oldHash = first.row().getCodeHash();
        service.send("a@b.co");
        assertThat(first.row().getCodeHash()).isNotEqualTo(oldHash);
        assertThat(first.row().getAttempts()).isZero();
        assertThat(first.row().getVerifiedAt()).isNull();
        assertThat(first.row().getTokenHash()).isNull();
    }

    @Test
    void 메일을_못_보내면_NOTIFICATION_UNAVAILABLE() {
        when(verifications.findByEmail("a@b.co")).thenReturn(Optional.empty());
        when(verifications.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(notifications.enqueue(any())).thenReturn(sent("FAILED"));
        assertThatThrownBy(() -> service.send("a@b.co")).extracting("errorCode").isEqualTo(ErrorCode.NOTIFICATION_UNAVAILABLE);
    }

    @Test
    void 맞는_코드면_인증_완료_토큰을_받고_그_토큰으로만_가입할_수_있다() {
        Issued i = sendFresh("a@b.co");
        when(verifications.findByEmail("a@b.co")).thenReturn(Optional.of(i.row()));

        String token = service.confirm("A@b.co", i.code()).verificationToken();

        assertThat(token).hasSizeGreaterThanOrEqualTo(40);
        assertThat(i.row().getTokenHash()).hasSize(64).doesNotContain(token);   // 토큰도 해시로만
        assertThatCode(() -> service.assertVerified("a@b.co", token)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.assertVerified("a@b.co", "wrong-token")).extracting("errorCode").isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);
        assertThatThrownBy(() -> service.assertVerified("a@b.co", null)).extracting("errorCode").isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);
        // 토큰 유효 시간(10분)이 지나면 가입할 수 없다
        clock.plus(Duration.ofMinutes(10));
        assertThatThrownBy(() -> service.assertVerified("a@b.co", token)).extracting("errorCode").isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);
        service.consume("a@b.co");
        verify(verifications).deleteByEmail("a@b.co");
    }

    @Test
    void 새_코드를_보내면_이전_코드와_토큰은_무효가_된다() {
        Issued i = sendFresh("a@b.co");
        when(verifications.findByEmail("a@b.co")).thenReturn(Optional.of(i.row()));
        String token = service.confirm("a@b.co", i.code()).verificationToken();

        clock.plus(Duration.ofSeconds(61));
        service.send("a@b.co");

        assertThatThrownBy(() -> service.confirm("a@b.co", i.code())).extracting("errorCode").isEqualTo(ErrorCode.VERIFICATION_CODE_INVALID);
        assertThatThrownBy(() -> service.assertVerified("a@b.co", token)).extracting("errorCode").isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);
    }

    @Test
    void 하루_발송_횟수를_넘으면_거부하고_다음_날_다시_셀_수_있다() {
        Issued i = sendFresh("a@b.co");                       // 1회
        when(verifications.findByEmail("a@b.co")).thenReturn(Optional.of(i.row()));
        clock.plus(Duration.ofSeconds(61)); service.send("a@b.co");   // 2회
        clock.plus(Duration.ofSeconds(61)); service.send("a@b.co");   // 3회 (상한)
        clock.plus(Duration.ofSeconds(61));
        assertThatThrownBy(() -> service.send("a@b.co")).extracting("errorCode").isEqualTo(ErrorCode.VERIFICATION_DAILY_LIMIT);

        clock.plus(Duration.ofDays(1));
        assertThatCode(() -> service.send("a@b.co")).doesNotThrowAnyException();
    }

    @Test
    void 틀린_코드는_횟수를_세고_상한을_넘으면_다시_받아야_한다() {
        Issued i = sendFresh("a@b.co");
        when(verifications.findByEmail("a@b.co")).thenReturn(Optional.of(i.row()));
        String wrong = i.code().equals("000000") ? "000001" : "000000";

        assertThatThrownBy(() -> service.confirm("a@b.co", wrong)).extracting("errorCode").isEqualTo(ErrorCode.VERIFICATION_CODE_INVALID);
        assertThatThrownBy(() -> service.confirm("a@b.co", wrong)).extracting("errorCode").isEqualTo(ErrorCode.VERIFICATION_CODE_INVALID);
        assertThatThrownBy(() -> service.confirm("a@b.co", wrong)).extracting("errorCode").isEqualTo(ErrorCode.VERIFICATION_TOO_MANY_ATTEMPTS);
        // 상한에 닿으면 맞는 코드도 더는 받지 않는다
        assertThatThrownBy(() -> service.confirm("a@b.co", i.code())).extracting("errorCode").isEqualTo(ErrorCode.VERIFICATION_TOO_MANY_ATTEMPTS);
        assertThatThrownBy(() -> service.assertVerified("a@b.co", "any")).extracting("errorCode").isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);
    }

    @Test
    void 만료된_코드는_거부한다() {
        Issued i = sendFresh("a@b.co");
        when(verifications.findByEmail("a@b.co")).thenReturn(Optional.of(i.row()));

        clock.plus(Duration.ofMinutes(10));
        assertThatThrownBy(() -> service.confirm("a@b.co", i.code())).extracting("errorCode").isEqualTo(ErrorCode.VERIFICATION_CODE_INVALID);
    }

    @Test
    void 인증_기록이_없으면_가입할_수_없다() {
        when(verifications.findByEmail("a@b.co")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.assertVerified("a@b.co", "tok")).extracting("errorCode").isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);
        assertThatThrownBy(() -> service.confirm("a@b.co", "123456")).extracting("errorCode").isEqualTo(ErrorCode.VERIFICATION_CODE_INVALID);
    }
}
