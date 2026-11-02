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
import com.zipick.notification.dto.NotificationSettingResponse;
import com.zipick.notification.repository.NotificationSettingRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 알림 설정은 메인 DB 에, 발송은 알림센터에 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @org.junit.jupiter.api.BeforeEach
    void slackAllowedByDefault() {
        org.mockito.Mockito.lenient().when(policy.slackAllowed(org.mockito.ArgumentMatchers.any())).thenReturn(true);
    }


    @Mock NotificationSettingRepository settings;
    @Mock NotificationClient client;
    @Mock NotificationChannelPolicy policy;
    @InjectMocks NotificationService service;

    private static NotificationSettingRequest req(String webhook, String email, Boolean marketing) {
        return new NotificationSettingRequest(true, webhook, email, marketing, "3,1,0", true);
    }

    @Test
    void 슬랙_주소가_아니면_저장하지_않고_거부한다() {
        assertThatThrownBy(() -> service.saveSetting(1L, req("https://evil.example/x", null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("hooks.slack.com");
        verify(settings, never()).save(any());
    }

    @Test
    void 처음_저장하면_기본값에서_시작하고_광고_동의_시각을_남긴다() {
        when(settings.findById(1L)).thenReturn(Optional.empty());
        when(settings.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(client.emailAvailable()).thenReturn(true);

        NotificationSettingResponse r = service.saveSetting(1L, req("https://hooks.slack.com/services/T/B/x", "a@b.co", true));

        assertThat(r.userId()).isEqualTo(1L);
        assertThat(r.slackConfigured()).isTrue();
        assertThat(r.slackWebhookMasked()).isEqualTo("…T/B/x".substring(0, 1) + "s/T/B/x".substring(1));
        assertThat(r.email()).isEqualTo("a@b.co");
        assertThat(r.marketingConsent()).isTrue();
        assertThat(r.marketingConsentedAt()).isNotNull();
        assertThat(r.deadlineDays()).containsExactly(3, 1, 0);
        assertThat(r.emailAvailable()).isTrue();
    }

    @Test
    void 주소는_null_이면_유지_빈_문자열이면_삭제한다() {
        NotificationSetting s = NotificationSetting.defaults(1L);
        s.apply(req("https://hooks.slack.com/services/x", "a@b.co", true), LocalDateTime.now());
        LocalDateTime consentedAt = s.getMarketingConsentedAt();
        when(settings.findById(1L)).thenReturn(Optional.of(s));
        when(settings.save(any())).thenAnswer(inv -> inv.getArgument(0));

        NotificationSettingResponse r = service.saveSetting(1L, req(null, "", true));

        assertThat(r.slackConfigured()).isTrue();
        assertThat(r.email()).isNull();
        assertThat(r.marketingConsentedAt()).isEqualTo(consentedAt);   // 동의가 그대로면 시각도 그대로

        r = service.saveSetting(1L, req("", null, false));
        assertThat(r.slackConfigured()).isFalse();
        assertThat(r.marketingConsent()).isFalse();
        assertThat(r.marketingConsentedAt()).isNull();
    }

    @Test
    void 설정이_없으면_기본값을_보여_준다() {
        when(settings.findById(1L)).thenReturn(Optional.empty());
        when(client.emailAvailable()).thenReturn(false);
        NotificationSettingResponse r = service.getSetting(1L);
        assertThat(r.enabled()).isFalse();
        assertThat(r.deadlineDays()).containsExactly(3, 1, 0);
        assertThat(r.newMatchEnabled()).isTrue();
        assertThat(r.emailAvailable()).isFalse();
    }
}
