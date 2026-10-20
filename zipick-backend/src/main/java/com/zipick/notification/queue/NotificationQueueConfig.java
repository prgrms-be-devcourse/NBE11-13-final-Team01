package com.zipick.notification.queue;

import java.util.Map;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 알림 요청 큐 선언. 알림센터(Go)와 **같은 이름·같은 인자**여야 한다 — 다르면 브로커가 선언을 거부한다.
 * RabbitAdmin 이 첫 연결 때 선언하므로 메인 서버와 알림센터 중 누가 먼저 떠도 된다.
 */
@Configuration
@ConditionalOnProperty(prefix = "zipick.notification", name = "queue-enabled", havingValue = "true")
public class NotificationQueueConfig {

    public static final String QUEUE = "notification.requests";
    public static final String DLQ = "notification.requests.dlq";
    public static final int MAX_PRIORITY = 9;

    /** 요청 큐: 우선순위 0~9, 알림센터가 거부한 메시지는 실패 큐로 */
    @Bean
    public Queue notificationRequestQueue() {
        return new Queue(QUEUE, true, false, false, Map.of(
                "x-max-priority", MAX_PRIORITY,
                "x-dead-letter-exchange", "",
                "x-dead-letter-routing-key", DLQ));
    }

    /** 실패 큐: 형식이 잘못된 요청이 모인다. 운영자가 보고 지운다 */
    @Bean
    public Queue notificationRequestDlq() {
        return new Queue(DLQ, true);
    }
}
