package com.zipick.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** 광고성 정보는 21~08시에 보내지 않는다 — 요청하는 쪽(메인 서버)의 판단 */
class MarketingWindowTest {

    @Test
    void 낮에는_그대로_밤에는_다음_08시로() {
        LocalDateTime noon = LocalDateTime.of(2026, 10, 23, 12, 0);
        assertThat(MarketingWindow.nextAllowed(noon)).isEqualTo(noon);
        assertThat(MarketingWindow.nextAllowed(LocalDateTime.of(2026, 10, 23, 21, 0))).isEqualTo(LocalDateTime.of(2026, 10, 24, 8, 0));
        assertThat(MarketingWindow.nextAllowed(LocalDateTime.of(2026, 10, 23, 23, 30))).isEqualTo(LocalDateTime.of(2026, 10, 24, 8, 0));
        assertThat(MarketingWindow.nextAllowed(LocalDateTime.of(2026, 10, 24, 3, 0))).isEqualTo(LocalDateTime.of(2026, 10, 24, 8, 0));
        assertThat(MarketingWindow.nextAllowed(LocalDateTime.of(2026, 10, 24, 8, 0))).isEqualTo(LocalDateTime.of(2026, 10, 24, 8, 0));
        assertThat(MarketingWindow.nextAllowed(LocalDateTime.of(2026, 10, 24, 20, 59))).isEqualTo(LocalDateTime.of(2026, 10, 24, 20, 59));
    }
}
