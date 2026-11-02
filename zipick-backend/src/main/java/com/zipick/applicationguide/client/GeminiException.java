package com.zipick.applicationguide.client;

import com.zipick.applicationguide.dto.GuideUnavailableReason;
import lombok.Getter;

/**
 * Gemini 호출 실패.
 *
 * 공고문 읽기 실패(GuideDocumentException)와 같은 이유로 BusinessException 을 쓰지 않는다.
 * AI 가 실패해도 HTTP 오류가 아니라 "가이드를 못 만들었다" 는 상태로 내려야,
 * 사용자가 그 화면에서 공고 원문으로 넘어갈 수 있다.
 */
@Getter
public class GeminiException extends RuntimeException {

    private final GuideUnavailableReason reason;

    public GeminiException(GuideUnavailableReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public GeminiException(GuideUnavailableReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }
}
