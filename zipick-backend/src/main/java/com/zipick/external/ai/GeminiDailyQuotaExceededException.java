package com.zipick.external.ai;

public class GeminiDailyQuotaExceededException extends IllegalStateException {
    public GeminiDailyQuotaExceededException(String message) {
        super(message);
    }
}
