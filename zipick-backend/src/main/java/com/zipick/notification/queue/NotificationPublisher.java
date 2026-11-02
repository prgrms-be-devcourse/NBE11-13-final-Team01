package com.zipick.notification.queue;

import com.zipick.global.util.JsonUtil;
import com.zipick.notification.client.NotificationRequest;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 알림 요청을 RabbitMQ 에 던진다. 응답을 기다리지 않는다.
 *
 * 메시지 본문은 HTTP 로 보낼 때와 같은 JSON(NotificationRequest)이라 알림센터는 한 코드로 둘 다 받는다.
 * 영속(persistent) + 발행 확인(publisher confirm): 브로커가 디스크에 적었다고 확인해 줘야 true. 확인이 없으면 false 를 돌려주고
 * 호출한 쪽(NotificationClient)이 HTTP 로 한 번 더 보낸다 — 브로커가 죽어 있을 때의 대비책.
 */
@Component
@ConditionalOnProperty(prefix = "zipick.notification", name = "queue-enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class NotificationPublisher {

    private static final long CONFIRM_TIMEOUT_MS = 2000;

    private final RabbitTemplate rabbitTemplate;

    /** @return 브로커가 받았다고 확인했으면 true */
    public boolean publish(NotificationRequest request, NotificationPriority priority) {
        byte[] body = JsonUtil.write(request).getBytes(StandardCharsets.UTF_8);
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding("UTF-8");
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setPriority(priority.value);
        Message message = new Message(body, props);
        try {
            Boolean confirmed = rabbitTemplate.invoke(ops -> {
                ops.send("", NotificationQueueConfig.QUEUE, message);
                return ops.waitForConfirms(CONFIRM_TIMEOUT_MS);
            });
            if (!Boolean.TRUE.equals(confirmed)) log.warn("브로커가 발행을 확인하지 않음: template={}", request.templateCode());
            return Boolean.TRUE.equals(confirmed);
        } catch (Exception e) {
            log.warn("큐 발행 실패 (HTTP 로 대신 보냄): template={} err={}", request.templateCode(), e.toString());
            return false;
        }
    }
}
