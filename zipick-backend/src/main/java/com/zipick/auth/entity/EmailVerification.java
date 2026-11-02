package com.zipick.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원가입 전 이메일 인증. 이메일 하나에 행 하나 — 다시 보내면 코드와 시각이 갱신되고 이전 코드는 무효가 된다.
 * 코드와 인증 완료 토큰은 해시로만 저장한다 (DB 가 새어도 값을 알 수 없게). 가입이 끝나면 행을 지운다.
 */
@Entity
@Getter
@Table(name = "email_verifications")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** 코드를 틀린 횟수. 상한을 넘으면 이 코드는 무효 */
    @Column(nullable = false)
    private int attempts;

    /** 하루 발송 횟수와 그 날짜 (날짜가 바뀌면 0부터) */
    @Column(name = "send_count", nullable = false)
    private int sendCount;

    @Column(name = "send_count_date", nullable = false)
    private LocalDate sendCountDate;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    /** 코드 확인 뒤 발급한 "인증 완료 토큰"의 해시. 가입 요청이 이 토큰을 들고 와야 한다 */
    @Column(name = "token_hash", length = 64)
    private String tokenHash;

    @Column(name = "token_expires_at")
    private LocalDateTime tokenExpiresAt;

    public static EmailVerification issue(String email, String codeHash, LocalDateTime now, LocalDateTime expiresAt) {
        EmailVerification v = new EmailVerification();
        v.email = email;
        v.sendCount = 0;
        v.sendCountDate = now.toLocalDate();
        v.reissue(codeHash, now, expiresAt);
        return v;
    }

    /** 새 코드로 바꾼다. 이전 코드·인증·토큰은 모두 무효, 발송 횟수는 하루 단위로 센다 */
    public void reissue(String codeHash, LocalDateTime now, LocalDateTime expiresAt) {
        if (!now.toLocalDate().equals(sendCountDate)) {
            sendCountDate = now.toLocalDate();
            sendCount = 0;
        }
        sendCount++;
        this.codeHash = codeHash;
        this.sentAt = now;
        this.expiresAt = expiresAt;
        this.attempts = 0;
        this.verifiedAt = null;
        this.tokenHash = null;
        this.tokenExpiresAt = null;
    }

    /** 오늘 보낸 횟수 (날짜가 지났으면 0) */
    public int sendsToday(LocalDate today) { return today.equals(sendCountDate) ? sendCount : 0; }

    public boolean isExpired(LocalDateTime now) { return !now.isBefore(expiresAt); }

    public void recordFailure() { this.attempts++; }

    /** 코드가 맞았다: 인증 완료 토큰(해시)을 발급한다 */
    public void markVerified(String tokenHash, LocalDateTime now, LocalDateTime tokenExpiresAt) {
        this.verifiedAt = now;
        this.tokenHash = tokenHash;
        this.tokenExpiresAt = tokenExpiresAt;
    }

    public boolean tokenValid(String tokenHash, LocalDateTime now) {
        return this.tokenHash != null && tokenExpiresAt != null && now.isBefore(tokenExpiresAt)
                && java.security.MessageDigest.isEqual(this.tokenHash.getBytes(), tokenHash.getBytes());
    }
}
