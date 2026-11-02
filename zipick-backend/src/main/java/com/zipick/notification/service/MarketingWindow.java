package com.zipick.notification.service;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 광고성 정보는 21시~다음날 08시에 보내지 않는다 (정보통신망법 제50조: 야간 전송은 별도 동의 필요).
 * 이 판단은 알림센터가 아니라 요청하는 쪽(메인 서버)의 몫이다 — 캠페인 예약 시각을 정할 때 쓴다.
 */
public final class MarketingWindow {

    public static final LocalTime QUIET_START = LocalTime.of(21, 0);
    public static final LocalTime QUIET_END = LocalTime.of(8, 0);

    private MarketingWindow() {}

    public static boolean isQuiet(LocalDateTime t) {
        LocalTime time = t.toLocalTime();
        return !time.isBefore(QUIET_START) || time.isBefore(QUIET_END);
    }

    /** t 가 금지 시간대면 다음 08:00, 아니면 t 그대로 */
    public static LocalDateTime nextAllowed(LocalDateTime t) {
        if (!isQuiet(t)) return t;
        LocalDateTime next = t.toLocalDate().atTime(QUIET_END);
        return t.toLocalTime().isBefore(QUIET_END) ? next : next.plusDays(1);
    }
}
