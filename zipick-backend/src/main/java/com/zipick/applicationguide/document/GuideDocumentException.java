package com.zipick.applicationguide.document;

import com.zipick.applicationguide.dto.GuideUnavailableReason;
import lombok.Getter;

/**
 * 공고문을 읽지 못했을 때.
 *
 * BusinessException 을 쓰지 않는 이유는, 이게 HTTP 오류로 나가면 안 되기 때문이다.
 * 가이드를 못 만든 것은 장애가 아니라 화면이 그려야 할 상태(UNAVAILABLE)이고,
 * 사용자는 그 상황에서도 공고 원문을 볼 수 있어야 한다.
 * 서비스가 이 예외를 잡아 reason 을 그대로 응답 상태로 옮긴다.
 */
@Getter
public class GuideDocumentException extends RuntimeException {

    private final GuideUnavailableReason reason;

    public GuideDocumentException(GuideUnavailableReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public GuideDocumentException(GuideUnavailableReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }
}
