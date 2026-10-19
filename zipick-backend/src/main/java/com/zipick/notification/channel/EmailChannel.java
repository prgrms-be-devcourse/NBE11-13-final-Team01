package com.zipick.notification.channel;

import com.zipick.notification.domain.NotificationEvent;
import com.zipick.notification.domain.NotificationSetting;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** 이메일. spring.mail.host 가 설정돼 JavaMailSender 가 있을 때만 켜진다. */
@Component
@ConditionalOnBean(JavaMailSender.class)
@RequiredArgsConstructor
public class EmailChannel implements NotificationChannel {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String from;

    @Override
    public String name() { return "email"; }

    @Override
    public boolean supports(NotificationSetting s) { return s.hasEmail(); }

    @Override
    public void send(NotificationSetting s, NotificationEvent e) {
        SimpleMailMessage m = new SimpleMailMessage();
        if (!from.isBlank()) m.setFrom(from);
        m.setTo(s.getEmail());
        m.setSubject("[ZIPICK] " + e.getTitle());
        m.setText(e.getBody() + (e.getUrl() == null ? "" : "\n\n" + e.getUrl()));
        mailSender.send(m);
    }
}
