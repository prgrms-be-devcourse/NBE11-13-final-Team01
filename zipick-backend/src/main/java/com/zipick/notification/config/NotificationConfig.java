package com.zipick.notification.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationConfig {

    /** 알림센터 호출용. 알림센터가 느려도 요청 스레드를 오래 잡지 않게 짧은 타임아웃 */
    @Bean
    public RestClient notificationRestClient(NotificationProperties props) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory)
                .defaultHeader("X-Internal-Key", props.internalKey() == null ? "" : props.internalKey())
                .build();
    }
}
