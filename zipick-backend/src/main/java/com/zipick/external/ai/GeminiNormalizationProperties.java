package com.zipick.external.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Lite 추출 결과를 상위 모델로 검수할 때 사용하는 별도 호출 정책입니다. */
@ConfigurationProperties(prefix = "zipick.gemini-normalization")
public record GeminiNormalizationProperties(
        boolean enabled,
        String model,
        String thinkingLevel,
        int maxOutputTokens,
        int targetInputTokens,
        int maxInputTokens,
        int dailyCallLimit,
        int normalCallLimit,
        Duration timeout
) {
    public GeminiNormalizationProperties {
        model = model == null || model.isBlank() ? "gemini-3.8-flash" : model;
        thinkingLevel = thinkingLevel == null || thinkingLevel.isBlank() ? "low" : thinkingLevel;
        maxOutputTokens = maxOutputTokens <= 0 ? 16_384 : Math.min(maxOutputTokens, 65_536);
        targetInputTokens = targetInputTokens <= 0 ? 220_000 : targetInputTokens;
        maxInputTokens = maxInputTokens <= 0 ? 235_000 : Math.max(targetInputTokens, maxInputTokens);
        dailyCallLimit = dailyCallLimit <= 0 ? 20 : dailyCallLimit;
        normalCallLimit = normalCallLimit <= 0 ? 18 : Math.min(normalCallLimit, dailyCallLimit);
        timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
    }
}
