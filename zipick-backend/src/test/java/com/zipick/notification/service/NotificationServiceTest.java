package com.zipick.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zipick.global.error.BusinessException;
import com.zipick.notification.client.NotificationClient;
import com.zipick.notification.client.NotificationRequest;
import com.zipick.notification.domain.NotificationSetting;
import com.zipick.notification.dto.NotificationEventResponse;
import com.zipick.notification.dto.NotificationSettingRequest;
import com.zipick.notification.dto.NotificationSettingResponse;
import com.zipick.notification.repository.NotificationSettingRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 알림 설정은 메인 DB 에, 발송은 알림센터에. 테스트 발송은 저장된 주소로 delivery=now */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock NotificationSettingRepository settings;
    @Mock NotificationClient client;
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
    void 테스트_발송은_저장된_주소로_바로_보내고_결과를_받는다() {
        NotificationSetting s = NotificationSetting.defaults(1L);
        s.apply(req("https://hooks.slack.com/services/x", "a@b.co", null), LocalDateTime.now());
        when(settings.findById(1L)).thenReturn(Optional.of(s));
        when(client.usableChannels(List.of("email", "slack"))).thenReturn(List.of("email", "slack"));
        NotificationEventResponse sent = new NotificationEventResponse(7L, "main", "MAIN_TEST", null, List.of("email", "slack"), "t", "SENT",
                List.of(), List.of(), java.util.Map.of("user", "1"), LocalDateTime.now(), LocalDateTime.now(), false);
        when(client.enqueue(any())).thenReturn(sent);

        assertThat(service.sendTest(1L)).isSameAs(sent);

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(client).enqueue(captor.capture());
        NotificationRequest r = captor.getValue();
        assertThat(r.templateCode()).isEqualTo("MAIN_TEST");
        assertThat(r.channels()).containsExactly("email", "slack");
        assertThat(r.to().email()).isEqualTo("a@b.co");
        assertThat(r.delivery()).isEqualTo("now");
        assertThat(r.variables()).containsEntry("deadlineDays", "3, 1, 0");
        assertThat(r.tags()).containsEntry("user", "1");
    }

    @Test
    void 알림센터가_보낼_수_있는_채널만_고른다() {
        NotificationSetting s = NotificationSetting.defaults(1L);
        s.apply(req("https://hooks.slack.com/services/x", "a@b.co", null), LocalDateTime.now());
        when(settings.findById(1L)).thenReturn(Optional.of(s));
        when(client.usableChannels(List.of("email", "slack"))).thenReturn(List.of("slack"));   // 이메일 provider=none
        when(client.enqueue(any())).thenAnswer(inv -> null);

        service.sendTest(1L);

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(client).enqueue(captor.capture());
        assertThat(captor.getValue().channels()).containsExactly("slack");
    }

    @Test
    void 보낼_수_있는_채널이_없으면_거부한다() {
        NotificationSetting s = NotificationSetting.defaults(1L);
        s.apply(req(null, "a@b.co", null), LocalDateTime.now());
        when(settings.findById(1L)).thenReturn(Optional.of(s));
        when(client.usableChannels(List.of("email"))).thenReturn(List.of());
        assertThatThrownBy(() -> service.sendTest(1L)).isInstanceOf(BusinessException.class).hasMessageContaining("보낼 수 있는 채널");
        verify(client, never()).enqueue(any());
    }

    @Test
    void 주소가_없으면_테스트_발송을_거부한다() {
        when(settings.findById(1L)).thenReturn(Optional.of(NotificationSetting.defaults(1L)));
        assertThatThrownBy(() -> service.sendTest(1L)).isInstanceOf(BusinessException.class).hasMessageContaining("먼저 저장");
        verify(client, never()).enqueue(any());
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
