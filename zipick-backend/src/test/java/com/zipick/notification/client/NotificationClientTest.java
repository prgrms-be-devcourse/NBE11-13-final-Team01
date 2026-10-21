package com.zipick.notification.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.notification.config.NotificationProperties;
import com.zipick.notification.dto.NotificationSettingResponse;
import com.zipick.notification.queue.NotificationPriority;
import com.zipick.notification.queue.NotificationPublisher;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 메인 서버 → 알림센터 클라이언트.
 * 알림 요청은 큐가 먼저, 큐가 안 되면 HTTP. 조회·설정은 HTTP. 알림센터 응답 형식(success/data/error)을 해석한다.
 * 실제 알림센터 대신 MockRestServiceServer 가 HTTP 응답을 흉내 낸다.
 */
class NotificationClientTest {

    private static final String KEY = "test-key";
    private MockRestServiceServer server;
    private RestClient restClient;
    private NotificationPublisher publisher;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://notification").defaultHeader("X-Internal-Key", KEY);
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
        publisher = mock(NotificationPublisher.class);
    }

    private NotificationClient client(String key, boolean withQueue) {
        return new NotificationClient(restClient, new NotificationProperties("http://notification", key, Duration.ofMinutes(5), true, withQueue),
                withQueue ? Optional.of(publisher) : Optional.empty());
    }

    private static final String EVENT_JSON = """
            {"success":true,"data":{"id":7,"userId":1,"templateCode":"DEADLINE","category":"INFORMATIONAL","campaignId":null,
             "title":"접수 마감 D-3","body":"본문","url":null,"status":"PENDING","attempts":0,"lastError":null,"channels":null,
             "scheduledFor":"2026-10-20T10:06:46.543519","sentAt":null,"duplicate":false},"error":null}""";

    private static final String SUBS_JSON = """
            {"success":true,"data":[{"userId":1,"enabled":true,"slackConfigured":true,"slackWebhookMasked":"…abcdef","email":null,
             "marketingConsent":false,"marketingConsentedAt":null,"deadlineDays":[3,1,0],"newMatchEnabled":true,"emailAvailable":false}],"error":null}""";

    private static NotificationRequest request() {
        return NotificationRequest.forUser("DEADLINE", 1L, Map.of("dDayLabel", "D-3"), "DEADLINE:1:10:3");
    }

    @Test
    void 큐가_받으면_HTTP_를_부르지_않는다() {
        when(publisher.publish(any(), any())).thenReturn(true);
        assertThat(client(KEY, true).request(request(), NotificationPriority.NORMAL)).isTrue();
        server.verify();   // 기대한 HTTP 호출이 없어야 통과
    }

    @Test
    void 큐가_실패하면_HTTP_로_대신_보낸다() {
        when(publisher.publish(any(), any())).thenReturn(false);
        server.expect(requestTo("http://notification/internal/notifications")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Key", KEY))
                .andRespond(withSuccess(EVENT_JSON, MediaType.APPLICATION_JSON));

        assertThat(client(KEY, true).request(request(), NotificationPriority.HIGH)).isTrue();
        server.verify();
    }

    @Test
    void 큐가_꺼져_있으면_HTTP_로_보내고_응답을_해석한다() {
        server.expect(requestTo("http://notification/internal/notifications"))
                .andRespond(withSuccess(EVENT_JSON, MediaType.APPLICATION_JSON));
        var r = client(KEY, false).enqueue(request());
        assertThat(r.id()).isEqualTo(7L);
        assertThat(r.templateCode()).isEqualTo("DEADLINE");
        assertThat(r.scheduledFor().getHour()).isEqualTo(10);
    }

    @Test
    void 내부_키가_없으면_부르지_않고_NOTIFICATION_UNAVAILABLE() {
        NotificationClient c = client("", false);
        assertThat(c.configured()).isFalse();
        assertThatThrownBy(() -> c.getSubscription(1L))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.NOTIFICATION_UNAVAILABLE);
    }

    @Test
    void 알림센터가_400_으로_거부하면_그_메시지를_INVALID_INPUT_으로_전달한다() {
        server.expect(requestTo("http://notification/internal/subscriptions/1")).andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":false,\"data\":null,\"error\":{\"code\":\"INVALID_INPUT\",\"message\":\"슬랙 웹훅 주소 오류\",\"fields\":null}}"));

        assertThatThrownBy(() -> client(KEY, false).putSubscription(1L,
                new com.zipick.notification.dto.NotificationSettingRequest(true, "https://x", null, null, "3,1,0", true)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("슬랙 웹훅 주소 오류")
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void 알림센터_5xx_는_NOTIFICATION_UNAVAILABLE() {
        server.expect(requestTo("http://notification/internal/subscriptions/1/test")).andRespond(withServerError());
        assertThatThrownBy(() -> client(KEY, false).sendTest(1L))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.NOTIFICATION_UNAVAILABLE);
    }

    @Test
    void 성공_200_이지만_success_false_면_INVALID_INPUT() {
        server.expect(requestTo("http://notification/internal/notifications?userId=1"))
                .andRespond(withSuccess("{\"success\":false,\"data\":null,\"error\":{\"code\":\"X\",\"message\":\"거부\",\"fields\":null}}",
                        MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client(KEY, false).history(1L)).hasMessage("거부");
    }

    @Test
    void 구독자_목록은_알림센터가_안_닿으면_큐가_있을_때_마지막_목록을_쓴다() {
        NotificationClient c = client(KEY, true);
        server.expect(requestTo("http://notification/internal/subscriptions")).andRespond(withSuccess(SUBS_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://notification/internal/subscriptions")).andRespond(withServerError());

        List<NotificationSettingResponse> first = c.listEnabledSubscriptions();
        List<NotificationSettingResponse> second = c.listEnabledSubscriptions();

        assertThat(first).hasSize(1);
        assertThat(second).isEqualTo(first);
        assertThat(second.get(0).deadlineDays()).containsExactly(3, 1, 0);
    }

    @Test
    void 구독자_목록을_한_번도_못_받았으면_실패를_그대로_던진다() {
        server.expect(requestTo("http://notification/internal/subscriptions")).andRespond(withServerError());
        assertThatThrownBy(() -> client(KEY, true).listEnabledSubscriptions())
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.NOTIFICATION_UNAVAILABLE);
    }

    @Test
    void 큐가_꺼져_있으면_캐시를_쓰지_않는다() {
        NotificationClient c = client(KEY, false);
        server.expect(requestTo("http://notification/internal/subscriptions")).andRespond(withSuccess(SUBS_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://notification/internal/subscriptions")).andRespond(withServerError());
        c.listEnabledSubscriptions();
        assertThatThrownBy(c::listEnabledSubscriptions).isInstanceOf(BusinessException.class);
        verify(publisher, org.mockito.Mockito.never()).publish(any(), any());
    }
}
