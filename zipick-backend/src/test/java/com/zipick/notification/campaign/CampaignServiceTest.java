package com.zipick.notification.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zipick.global.error.BusinessException;
import com.zipick.notification.client.BatchRequest;
import com.zipick.notification.client.BatchResponse;
import com.zipick.notification.client.NotificationClient;
import com.zipick.notification.config.NotificationProperties;
import com.zipick.notification.domain.NotificationSetting;
import com.zipick.notification.dto.NotificationSettingRequest;
import com.zipick.notification.repository.NotificationSettingRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 캠페인: 대상(동의자)·시각(야간 회피)은 메인 서버가 정하고, 알림센터에는 묶음 요청만 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class CampaignServiceTest {

    @Mock CampaignRepository campaigns;
    @Mock NotificationSettingRepository settings;
    @Mock NotificationClient client;
    @Mock com.zipick.notification.service.NotificationChannelPolicy policy;
    CampaignService service;

    @BeforeEach
    void setUp() {
        service = new CampaignService(campaigns, settings, client, policy,
                new NotificationProperties(null, "k", Duration.ofMinutes(5), true, false, "https://zipick.example", null, null));
        org.mockito.Mockito.lenient().when(client.usableChannels(any())).thenAnswer(inv -> inv.getArgument(0));
        // 기존 시나리오는 슬랙 수신자를 포함하므로 운영자(슬랙 허용)로 본다. 일반 사용자 제한은 NotificationChannelPolicyTest 에서
        org.mockito.Mockito.lenient().when(policy.slackAllowed(any())).thenReturn(true);
    }

    private static NotificationSetting consented(long userId, String webhook, String email) {
        NotificationSetting s = NotificationSetting.defaults(userId);
        s.apply(new NotificationSettingRequest(true, webhook, email, true, "3", true), LocalDateTime.now());
        return s;
    }

    private static BatchResponse batch(long id, long pending, long sent, long cancelled) {
        return new BatchResponse(id, "main", "MARKETING_NEWSLETTER", List.of("email"), LocalDateTime.now(), "SCHEDULED", Map.of(),
                pending + sent + cancelled, pending, sent, 0, 0, cancelled, 0, LocalDateTime.now());
    }

    @Test
    void 초안은_야간이면_다음_08시로_미뤄_저장한다() {
        when(campaigns.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CampaignResponse r = service.create(1L, new CampaignRequest("10월 소식", "새 공고 안내", "본문", LocalDateTime.of(2026, 10, 23, 22, 0)));
        assertThat(r.status()).isEqualTo(Campaign.Status.DRAFT);
        assertThat(r.scheduledAt()).isEqualTo(LocalDateTime.of(2026, 10, 24, 8, 0));
        assertThat(r.progress()).isNull();
    }

    @Test
    void 예약하면_동의자를_채널_조합별_묶음으로_넘긴다() {
        Campaign c = Campaign.draft("t", "h", "m", LocalDateTime.now().plusDays(1).withHour(10), 1L);
        when(campaigns.findById(5L)).thenReturn(Optional.of(c));
        when(settings.findAllByEnabledTrueAndMarketingConsentTrue()).thenReturn(List.of(
                consented(1, "https://hooks.slack.com/a", null),            // 슬랙만
                consented(2, null, "b@x.co"),                               // 이메일만
                consented(3, "https://hooks.slack.com/c", "c@x.co"),        // 둘 다
                consented(4, null, null)));                                 // 주소 없음 → 제외
        when(client.createBatch(any())).thenReturn(batch(11, 1, 0, 0), batch(12, 1, 0, 0), batch(13, 1, 0, 0));
        when(client.getBatch(anyLong())).thenReturn(batch(11, 1, 0, 0));

        CampaignResponse r = service.schedule(5L);

        assertThat(r.status()).isEqualTo(Campaign.Status.SCHEDULED);
        assertThat(r.recipientCount()).isEqualTo(3);
        assertThat(c.batchIdList()).containsExactly(11L, 12L, 13L);
        assertThat(r.progress().total()).isEqualTo(3);   // 묶음 3개 집계 합

        ArgumentCaptor<BatchRequest> captor = ArgumentCaptor.forClass(BatchRequest.class);
        verify(client, times(3)).createBatch(captor.capture());
        BatchRequest slackOnly = captor.getAllValues().get(0);
        assertThat(slackOnly.source()).isEqualTo("main");
        assertThat(slackOnly.templateCode()).isEqualTo("MARKETING_NEWSLETTER");
        assertThat(slackOnly.channels()).containsExactly("slack");
        assertThat(slackOnly.tags()).containsEntry("campaign", "5".replace("5", String.valueOf(c.getId())));
        assertThat(slackOnly.recipients()).hasSize(1);
        BatchRequest.Recipient rc = slackOnly.recipients().get(0);
        assertThat(rc.to().slackWebhookUrl()).isEqualTo("https://hooks.slack.com/a");
        assertThat(rc.variables()).containsEntry("headline", "h").containsEntry("message", "m").containsEntry("unsubscribeUrl", "https://zipick.example");
        assertThat(rc.tags()).containsEntry("user", "1");
        assertThat(captor.getAllValues().get(2).channels()).containsExactly("email", "slack");
        assertThat(slackOnly.scheduledAt()).isEqualTo(c.getScheduledAt());
    }

    @Test
    void 알림센터에_이메일이_꺼져_있으면_슬랙만_묶는다() {
        when(client.usableChannels(any())).thenAnswer(inv -> ((List<String>) inv.getArgument(0)).stream().filter("slack"::equals).toList());
        when(campaigns.findById(5L)).thenReturn(Optional.of(Campaign.draft("t", "h", "m", LocalDateTime.now().plusDays(1).withHour(10), 1L)));
        when(settings.findAllByEnabledTrueAndMarketingConsentTrue()).thenReturn(List.of(
                consented(2, null, "b@x.co"),                               // 이메일만 → 제외
                consented(3, "https://hooks.slack.com/c", "c@x.co")));      // 둘 다 → 슬랙만
        when(client.createBatch(any())).thenReturn(batch(11, 1, 0, 0));
        when(client.getBatch(anyLong())).thenReturn(batch(11, 1, 0, 0));

        CampaignResponse r = service.schedule(5L);

        assertThat(r.recipientCount()).isEqualTo(1);
        ArgumentCaptor<BatchRequest> captor = ArgumentCaptor.forClass(BatchRequest.class);
        verify(client).createBatch(captor.capture());
        assertThat(captor.getValue().channels()).containsExactly("slack");
    }

    @Test
    void 동의자가_없으면_예약을_거부한다() {
        when(campaigns.findById(5L)).thenReturn(Optional.of(Campaign.draft("t", "h", "m", LocalDateTime.now(), 1L)));
        when(settings.findAllByEnabledTrueAndMarketingConsentTrue()).thenReturn(List.of());
        assertThatThrownBy(() -> service.schedule(5L)).isInstanceOf(BusinessException.class).hasMessageContaining("동의한 사용자가 없습니다");
        verify(client, never()).createBatch(any());
    }

    @Test
    void 초안이_아니면_다시_예약할_수_없다() {
        Campaign c = Campaign.draft("t", "h", "m", LocalDateTime.now(), 1L);
        c.scheduled(List.of(11L), 1, LocalDateTime.now());
        when(campaigns.findById(5L)).thenReturn(Optional.of(c));
        assertThatThrownBy(() -> service.schedule(5L)).isInstanceOf(BusinessException.class);
    }

    @Test
    void 취소는_묶음마다_알림센터에_취소를_보낸다() {
        Campaign c = Campaign.draft("t", "h", "m", LocalDateTime.now(), 1L);
        c.scheduled(List.of(11L, 12L), 2, LocalDateTime.now());
        when(campaigns.findById(5L)).thenReturn(Optional.of(c));
        when(client.cancelBatch(anyLong())).thenReturn(batch(11, 0, 0, 1));
        when(client.getBatch(anyLong())).thenReturn(batch(11, 0, 1, 0), batch(12, 0, 0, 1));

        CampaignResponse r = service.cancel(5L);

        verify(client).cancelBatch(11L);
        verify(client).cancelBatch(12L);
        assertThat(r.status()).isEqualTo(Campaign.Status.CANCELLED);
        assertThat(r.progress().sent()).isEqualTo(1);        // 이미 나간 건 그대로
        assertThat(r.progress().cancelled()).isEqualTo(1);
    }

    @Test
    void 알림센터에_닿지_않으면_집계_없이_보여_준다() {
        Campaign c = Campaign.draft("t", "h", "m", LocalDateTime.now(), 1L);
        c.scheduled(List.of(11L), 1, LocalDateTime.now());
        when(campaigns.findById(5L)).thenReturn(Optional.of(c));
        when(client.getBatch(11L)).thenThrow(new BusinessException(com.zipick.global.error.ErrorCode.NOTIFICATION_UNAVAILABLE));
        assertThat(service.get(5L).progress()).isNull();
    }
}
