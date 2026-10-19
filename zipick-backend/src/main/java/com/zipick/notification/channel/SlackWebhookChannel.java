package com.zipick.notification.channel;

import com.zipick.notification.domain.NotificationEvent;
import com.zipick.notification.domain.NotificationSetting;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 슬랙 Incoming Webhook. 사용자가 설정에 넣은 URL 로 {"text": ...} 를 POST 한다. */
@Component
@RequiredArgsConstructor
public class SlackWebhookChannel implements NotificationChannel {

    private final RestClient slackRestClient;

    @Override
    public String name() { return "slack"; }

    @Override
    public boolean supports(NotificationSetting s) {
        return s.hasSlack() && s.getSlackWebhookUrl().startsWith(NotificationSetting.SLACK_WEBHOOK_PREFIX);
    }

    @Override
    public void send(NotificationSetting s, NotificationEvent e) {
        String text = "*" + e.getTitle() + "*\n" + e.getBody() + (e.getUrl() == null ? "" : "\n" + e.getUrl());
        slackRestClient.post().uri(s.getSlackWebhookUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("text", text))
                .retrieve()
                .toBodilessEntity();
    }
}
