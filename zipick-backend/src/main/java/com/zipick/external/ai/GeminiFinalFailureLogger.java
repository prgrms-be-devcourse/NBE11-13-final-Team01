package com.zipick.external.ai;

import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 재시도까지 끝난 Gemini 호출 실패만 기록한다. 응답 본문과 키는 기록하지 않는다. */
final class GeminiFinalFailureLogger {
    private static final Logger log = LoggerFactory.getLogger(GeminiFinalFailureLogger.class);
    private static final Pattern STATUS = Pattern.compile("status=(\\d{3})");

    private GeminiFinalFailureLogger() { }

    static void logFailure(String stage, RuntimeException exception) {
        String message = exception.getMessage();
        var matcher = STATUS.matcher(message == null ? "" : message);
        String status = matcher.find() ? matcher.group(1)
                : message != null && (message.contains("요청에 실패") || message.contains("중단되었습니다"))
                ? "network" : "other";
        log.warn("COLLECTOR_METRIC event=gemini_final_failure stage={} status={}", stage, status);
    }
}
