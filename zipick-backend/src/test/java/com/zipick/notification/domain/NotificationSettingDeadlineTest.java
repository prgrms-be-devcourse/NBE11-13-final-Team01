package com.zipick.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.zipick.notification.dto.NotificationSettingRequest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** 마감 알림 D-3·D-1·D-day 를 화면에서 하나씩 켜고 끈다. 셋 다 끄면 마감 알림을 보내지 않는다 */
class NotificationSettingDeadlineTest {

    private static NotificationSettingRequest days(String d) {
        return new NotificationSettingRequest(true, null, null, null, d, true);
    }

    @Test
    void 기본값은_3일_1일_당일() {
        NotificationSetting s = NotificationSetting.defaults(1L);
        assertThat(s.deadlineDayList()).containsExactly(3, 1, 0);
        assertThat(s.deadlineEnabled()).isTrue();
    }

    @Test
    void 일부만_켜면_그날만_보낸다() {
        NotificationSetting s = NotificationSetting.defaults(1L);
        s.apply(days("0,3"), LocalDateTime.now());
        assertThat(s.deadlineDayList()).containsExactly(3, 0);
    }

    @Test
    void 빈_문자열이면_마감_알림을_모두_끈다() {
        NotificationSetting s = NotificationSetting.defaults(1L);
        s.apply(days(""), LocalDateTime.now());
        assertThat(s.deadlineDayList()).isEmpty();
        assertThat(s.deadlineEnabled()).isFalse();
    }

    @Test
    void null_이면_그대로_둔다() {
        NotificationSetting s = NotificationSetting.defaults(1L);
        s.apply(days("1"), LocalDateTime.now());
        s.apply(days(null), LocalDateTime.now());
        assertThat(s.deadlineDayList()).containsExactly(1);
    }

    @Test
    void 공백과_중복을_정리하고_이른_알림부터_둔다() {
        assertThat(NotificationSetting.normalizeDays(" 1, 3,0,3 ")).isEqualTo("3,1,0");
        assertThat(NotificationSetting.normalizeDays(" ")).isEmpty();
    }
}
