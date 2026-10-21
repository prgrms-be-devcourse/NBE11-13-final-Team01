package com.zipick.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zipick.global.error.BusinessException;
import com.zipick.notification.client.NotificationClient;
import com.zipick.notification.dto.NotificationSettingRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 알림 설정 화면의 뒷단. 슬랙 웹훅 주소는 사용자에게 바로 알려 주려고 여기서도 한 번 거른다. */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock NotificationClient client;
    @InjectMocks NotificationService service;

    private static NotificationSettingRequest withWebhook(String url) {
        return new NotificationSettingRequest(true, url, null, null, "3,1,0", true);
    }

    @Test
    void 슬랙_주소가_아니면_알림센터로_보내지_않고_거부한다() {
        assertThatThrownBy(() -> service.saveSetting(1L, withWebhook("https://evil.example/x")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("hooks.slack.com");
        verify(client, never()).putSubscription(anyLong(), any());
    }

    @Test
    void 슬랙_주소이거나_비어_있으면_넘긴다() {
        service.saveSetting(1L, withWebhook("https://hooks.slack.com/services/T/B/x"));
        service.saveSetting(1L, withWebhook(""));      // 지우기
        service.saveSetting(1L, withWebhook(null));    // 그대로 두기
        verify(client, org.mockito.Mockito.times(3)).putSubscription(any(), any());
    }

    @Test
    void 조회_테스트_내역은_알림센터에_그대로_넘긴다() {
        when(client.history(1L)).thenReturn(List.of());
        service.getSetting(1L);
        service.sendTest(1L);
        assertThat(service.history(1L)).isEmpty();
        verify(client).getSubscription(1L);
        verify(client).sendTest(1L);
    }
}
