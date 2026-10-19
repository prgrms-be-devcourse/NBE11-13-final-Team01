package com.zipick.notification.channel;

import com.zipick.notification.domain.NotificationEvent;
import com.zipick.notification.domain.NotificationSetting;

/** 알림을 실제로 보내는 채널. 새 채널(카카오톡 등)은 이 인터페이스를 구현해 빈으로 등록하면 된다. */
public interface NotificationChannel {
    String name();
    boolean supports(NotificationSetting setting);
    /** 실패하면 예외를 던진다. 호출한 쪽이 attempts 를 올리고 다음 주기에 다시 시도한다 */
    void send(NotificationSetting setting, NotificationEvent event);
}
