package com.zipick.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 회원가입 이메일 인증 설정.
 * @param required        true 면 가입 전에 이메일 인증을 마쳐야 한다. 알림센터의 이메일 채널이 켜져 있어야 하므로 로컬 기본값은 false
 * @param codeTtl         인증 코드 유효 시간
 * @param tokenTtl        코드 확인 뒤 발급하는 "인증 완료 토큰"의 유효 시간 (이 안에 가입해야 한다)
 * @param resendCooldown  같은 이메일로 다시 보낼 수 있을 때까지
 * @param maxAttempts     코드 하나로 틀릴 수 있는 횟수. 넘으면 다시 받아야 한다
 * @param maxSendsPerDay  같은 이메일로 하루에 보낼 수 있는 횟수 (메일 폭탄·SES 비용 보호)
 */
@ConfigurationProperties(prefix = "zipick.auth.email-verification")
public record EmailVerificationProperties(boolean required, Duration codeTtl, Duration tokenTtl, Duration resendCooldown,
                                          int maxAttempts, int maxSendsPerDay) {
    public EmailVerificationProperties {
        if (codeTtl == null) codeTtl = Duration.ofMinutes(10);
        if (tokenTtl == null) tokenTtl = Duration.ofMinutes(10);
        if (resendCooldown == null) resendCooldown = Duration.ofSeconds(60);
        if (maxAttempts <= 0) maxAttempts = 5;
        if (maxSendsPerDay <= 0) maxSendsPerDay = 10;
    }
}
