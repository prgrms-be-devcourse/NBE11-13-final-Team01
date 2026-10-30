package com.zipick.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zipick.global.error.BusinessException;
import com.zipick.notification.client.NotificationClient;
import com.zipick.notification.domain.NotificationSetting;
import com.zipick.notification.dto.NotificationSettingRequest;
import com.zipick.notification.repository.NotificationSettingRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 슬랙은 운영자만, 일반 사용자는 이메일만 */
@ExtendWith(MockitoExtension.class)
class NotificationChannelPolicyTest {

    @Mock NotificationSettingRepository settings;
    @Mock NotificationClient client;
    @Mock NotificationChannelPolicy policy;
    @InjectMocks NotificationService service;

    private static NotificationSettingRequest req(String slack, String email) {
        return new NotificationSettingRequest(true, slack, email, null, "3,1,0", true);
    }

    @Test
    void 일반_사용자가_슬랙_웹훅을_넣으면_거부한다() {
        when(policy.slackAllowed(1L)).thenReturn(false);

        assertThatThrownBy(() -> service.saveSetting(1L, req("https://hooks.slack.com/services/x", "me@example.com")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("운영자");
        verify(settings, never()).save(any());
    }

    @Test
    void 일반_사용자는_이메일만_저장되고_남아_있던_웹훅은_지워진다() {
        when(policy.slackAllowed(1L)).thenReturn(false);
        NotificationSetting existing = NotificationSetting.defaults(1L);
        existing.apply(req("https://hooks.slack.com/services/old", "me@example.com"), java.time.LocalDateTime.now());
        when(settings.findById(1L)).thenReturn(Optional.of(existing));
        when(settings.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(client.emailAvailable()).thenReturn(true);

        var res = service.saveSetting(1L, req(null, "me@example.com"));

        assertThat(res.slackAllowed()).isFalse();
        assertThat(res.slackConfigured()).isFalse();
        assertThat(existing.channels(false)).containsExactly("email");
    }

    @Test
    void 운영자는_슬랙을_쓸_수_있다() {
        when(policy.slackAllowed(9L)).thenReturn(true);
        when(settings.findById(9L)).thenReturn(Optional.empty());
        when(settings.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(client.emailAvailable()).thenReturn(true);

        var res = service.saveSetting(9L, req("https://hooks.slack.com/services/x", null));

        assertThat(res.slackAllowed()).isTrue();
        assertThat(res.slackConfigured()).isTrue();
    }

    @Test
    void 채널_목록은_슬랙_허용_여부를_따른다() {
        NotificationSetting s = NotificationSetting.defaults(1L);
        s.apply(req("https://hooks.slack.com/services/x", "me@example.com"), java.time.LocalDateTime.now());

        assertThat(s.channels(true)).containsExactly("email", "slack");
        assertThat(s.channels(false)).containsExactly("email");
    }
}
