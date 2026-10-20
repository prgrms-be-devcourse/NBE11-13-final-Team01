package com.zipick.notification.queue;

/**
 * 큐 우선순위. 큰 값이 먼저 처리된다. 대량 캠페인이 쌓여 있어도 인증 메일이 뒤로 밀리지 않게 한다.
 * (캠페인은 알림센터 안에서 만들어져 큐를 거치지 않지만, 정보성 알림 수천 건 뒤에 인증 메일이 오는 경우를 막는다)
 */
public enum NotificationPriority {
    /** 가입 인증, 비밀번호 변경처럼 지금 당장 받아야 하는 것 */
    HIGH(9),
    /** 마감 D-day, 새 공고 */
    NORMAL(5),
    /** 광고·소식 */
    LOW(1);

    public final int value;

    NotificationPriority(int value) { this.value = value; }
}
