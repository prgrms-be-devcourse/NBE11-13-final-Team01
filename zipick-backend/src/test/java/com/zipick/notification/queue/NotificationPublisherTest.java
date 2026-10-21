package com.zipick.notification.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zipick.notification.client.NotificationRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * 알림 요청을 큐에 던진다. 영속 + 우선순위 + 발행 확인.
 * 브로커가 확인하지 않거나 연결이 안 되면 false → 호출한 쪽이 HTTP 로 대신 보낸다.
 * RabbitTemplate.invoke 는 "채널 하나를 잡고 그 안에서 실행"하는 콜백이라, 테스트에서는 콜백을 가짜 RabbitOperations 로 바로 실행한다.
 */
class NotificationPublisherTest {

    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final RabbitOperations ops = mock(RabbitOperations.class);
    private final NotificationPublisher publisher = new NotificationPublisher(template);

    private static NotificationRequest request() {
        return NotificationRequest.toEmail("EMAIL_VERIFY", "new@example.com", Map.of("code", "123456"));
    }

    @SuppressWarnings("unchecked")
    private void runCallbackWith(boolean confirmed) {
        when(ops.waitForConfirms(any(Long.class))).thenReturn(confirmed);
        when(template.invoke(any())).thenAnswer(inv ->
                ((RabbitOperations.OperationsCallback<Boolean>) inv.getArgument(0)).doInRabbit(ops));
    }

    @Test
    void 영속_메시지를_우선순위와_함께_요청_큐로_보내고_확인되면_true() {
        runCallbackWith(true);

        assertThat(publisher.publish(request(), NotificationPriority.HIGH)).isTrue();

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(ops).send(eq(""), eq(NotificationQueueConfig.QUEUE), captor.capture());
        Message m = captor.getValue();
        assertThat(m.getMessageProperties().getPriority()).isEqualTo(9);
        assertThat(m.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        String body = new String(m.getBody(), StandardCharsets.UTF_8);
        assertThat(body).contains("\"templateCode\":\"EMAIL_VERIFY\"").contains("\"email\":\"new@example.com\"");
    }

    @Test
    void 브로커가_확인하지_않으면_false() {
        runCallbackWith(false);
        assertThat(publisher.publish(request(), NotificationPriority.NORMAL)).isFalse();
    }

    @Test
    void 연결이_안_되면_예외_대신_false() {
        when(template.invoke(any())).thenThrow(new AmqpConnectException(new java.net.ConnectException("refused")));
        assertThat(publisher.publish(request(), NotificationPriority.LOW)).isFalse();
    }

    @Test
    void 우선순위_값은_거래성이_가장_크다() {
        assertThat(NotificationPriority.HIGH.value).isGreaterThan(NotificationPriority.NORMAL.value);
        assertThat(NotificationPriority.NORMAL.value).isGreaterThan(NotificationPriority.LOW.value);
        assertThat(NotificationPriority.HIGH.value).isLessThanOrEqualTo(NotificationQueueConfig.MAX_PRIORITY);
    }

    @Test
    void 큐_선언은_알림센터와_같은_인자를_쓴다() {
        var config = new NotificationQueueConfig();
        var queue = config.notificationRequestQueue();
        assertThat(queue.getName()).isEqualTo("notification.requests");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments()).containsEntry("x-max-priority", 9)
                .containsEntry("x-dead-letter-routing-key", "notification.requests.dlq");
        assertThat(config.notificationRequestDlq().getName()).isEqualTo("notification.requests.dlq");
    }
}
