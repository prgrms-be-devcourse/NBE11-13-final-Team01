package com.zipick.notification.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.notification.config.NotificationProperties;
import com.zipick.notification.domain.NotificationSetting;
import com.zipick.notification.dto.NotificationEventResponse;
import com.zipick.notification.dto.NotificationSettingRequest;
import com.zipick.notification.queue.NotificationPriority;
import com.zipick.notification.queue.NotificationPublisher;
import java.time.Duration;
import java.time.LocalDateTime;
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

/** 알림센터 v1 호출 경로·본문과, 큐 → HTTP 폴백, 오류 변환을 확인한다 */
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
        return new NotificationClient(restClient,
                new NotificationProperties("http://notification", key, Duration.ofMinutes(5), true, withQueue, null, null, null),
                withQueue ? Optional.of(publisher) : Optional.empty());
    }

    private static final String EVENT_JSON = """
            {"success":true,"data":{"id":7,"source":"main","templateCode":"NOTICE_DEADLINE_REMINDER","batchId":null,"channels":["slack"],
             "title":"접수 마감 D-3","status":"PENDING","deliveries":[{"channel":"slack","status":"PENDING","attempts":0,"lastError":null,"sentAt":null}],
             "missingVariables":[],"tags":{"user":"1"},"scheduledAt":"2026-10-23T10:06:46.543519","createdAt":"2026-10-23T10:06:46.543519","duplicate":false},"error":null}""";

    private static NotificationRequest request() {
        NotificationSetting s = NotificationSetting.defaults(1L);
        s.apply(new NotificationSettingRequest(true, "https://hooks.slack.com/services/x", null, null, "3", true), LocalDateTime.now());
        return NotificationRequest.toUser("NOTICE_DEADLINE_REMINDER", s, s.channels(), Map.of("dDayLabel", "D-3"), "DEADLINE:1:10:3");
    }

    @Test
    void 큐가_받으면_HTTP_를_부르지_않는다() {
        when(publisher.publish(any(), any())).thenReturn(true);
        client(KEY, true).request(request(), NotificationPriority.NORMAL);
        server.verify();   // 기대한 HTTP 요청이 없다
    }

    @Test
    void 큐가_실패하면_HTTP_v1_로_대신_보낸다() {
        when(publisher.publish(any(), any())).thenReturn(false);
        server.expect(requestTo("http://notification/internal/v1/notifications")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Key", KEY))
                .andExpect(content().json("""
                        {"source":"main","templateCode":"NOTICE_DEADLINE_REMINDER","channels":["slack"],
                         "to":{"email":null,"slackWebhookUrl":"https://hooks.slack.com/services/x"},
                         "variables":{"dDayLabel":"D-3"},"dedupeKey":"DEADLINE:1:10:3","scheduledAt":null,"tags":{"user":"1"},"delivery":null}"""))
                .andRespond(withSuccess(EVENT_JSON, MediaType.APPLICATION_JSON));
        client(KEY, true).request(request(), NotificationPriority.NORMAL);
        server.verify();
    }

    @Test
    void 응답을_해석한다_채널별_결과_포함() {
        server.expect(requestTo("http://notification/internal/v1/notifications"))
                .andRespond(withSuccess(EVENT_JSON, MediaType.APPLICATION_JSON));
        NotificationEventResponse r = client(KEY, false).enqueue(request());
        assertThat(r.id()).isEqualTo(7L);
        assertThat(r.channels()).containsExactly("slack");
        assertThat(r.deliveries()).hasSize(1);
        assertThat(r.deliveries().get(0).channel()).isEqualTo("slack");
        assertThat(r.tags()).containsEntry("user", "1");
    }

    @Test
    void 내역은_tag_user_로_조회한다() {
        server.expect(requestTo("http://notification/internal/v1/notifications?tag=user:1"))
                .andRespond(withSuccess("{\"success\":true,\"data\":[],\"error\":null}", MediaType.APPLICATION_JSON));
        assertThat(client(KEY, false).history(1L)).isEmpty();
    }

    @Test
    void 채널_상태는_닿지_않으면_마지막_값을_쓴다() {
        NotificationClient c = client(KEY, false);
        server.expect(requestTo("http://notification/internal/v1/channels"))
                .andRespond(withSuccess("{\"success\":true,\"data\":{\"email\":true,\"slack\":true},\"error\":null}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://notification/internal/v1/channels")).andRespond(withServerError());
        assertThat(c.emailAvailable()).isTrue();
        assertThat(c.emailAvailable()).isTrue();   // 두 번째는 5xx 지만 마지막 값
    }

    @Test
    void 채널_상태를_한_번도_못_받았으면_false_이고_원하는_채널은_그대로() {
        server.expect(requestTo("http://notification/internal/v1/channels")).andRespond(withServerError());
        server.expect(requestTo("http://notification/internal/v1/channels")).andRespond(withServerError());
        NotificationClient c = client(KEY, false);
        assertThat(c.emailAvailable()).isFalse();
        assertThat(c.usableChannels(List.of("email", "slack"))).containsExactly("email", "slack");
    }

    @Test
    void 꺼진_채널은_뺀다() {
        server.expect(requestTo("http://notification/internal/v1/channels"))
                .andRespond(withSuccess("{\"success\":true,\"data\":{\"email\":false,\"slack\":true},\"error\":null}", MediaType.APPLICATION_JSON));
        assertThat(client(KEY, false).usableChannels(List.of("email", "slack"))).containsExactly("slack");
    }

    @Test
    void 묶음은_만들고_조회하고_취소한다() {
        String batch = """
                {"success":true,"data":{"id":3,"source":"main","templateCode":"MARKETING_NEWSLETTER","channels":["email"],
                 "scheduledAt":"2026-10-24T08:00:00","status":"%s","tags":{"campaign":"1"},"total":2,"pending":%d,"sent":0,"partial":0,
                 "failed":0,"cancelled":%d,"skippedDuplicates":0,"createdAt":"2026-10-23T10:00:00"},"error":null}""";
        server.expect(requestTo("http://notification/internal/v1/batches")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(batch.formatted("SCHEDULED", 2, 0), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://notification/internal/v1/batches/3")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(batch.formatted("SCHEDULED", 2, 0), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://notification/internal/v1/batches/3")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess(batch.formatted("CANCELLED", 0, 2), MediaType.APPLICATION_JSON));
        NotificationClient c = client(KEY, false);
        BatchResponse created = c.createBatch(new BatchRequest("main", "MARKETING_NEWSLETTER", List.of("email"), null, Map.of(), List.of()));
        assertThat(created.id()).isEqualTo(3L);
        assertThat(c.getBatch(3).pending()).isEqualTo(2);
        assertThat(c.cancelBatch(3).cancelled()).isEqualTo(2);
    }

    @Test
    void 내부_키가_없으면_부르지_않고_NOTIFICATION_UNAVAILABLE() {
        assertThatThrownBy(() -> client("", false).enqueue(request()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOTIFICATION_UNAVAILABLE);
    }

    @Test
    void 알림센터가_400_으로_거부하면_그_메시지를_INVALID_INPUT_으로_전달한다() {
        server.expect(requestTo("http://notification/internal/v1/notifications"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":false,\"data\":null,\"error\":{\"code\":\"NO_RECIPIENT\",\"message\":\"slack 채널을 골랐는데 to 에 그 주소가 없습니다\"}}"));
        assertThatThrownBy(() -> client(KEY, false).enqueue(request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("주소가 없습니다")
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void 알림센터_5xx_는_NOTIFICATION_UNAVAILABLE() {
        server.expect(requestTo("http://notification/internal/v1/notifications")).andRespond(withServerError());
        assertThatThrownBy(() -> client(KEY, false).enqueue(request()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOTIFICATION_UNAVAILABLE);
    }
}
