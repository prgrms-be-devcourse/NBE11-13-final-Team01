package com.zipick.external.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.nio.file.Path;

@ConfigurationProperties(prefix = "zipick.gemini-pdf-parser")
public record GeminiPdfParserProperties(
        String key,
        String baseUrl,
        String model,
        boolean enabled,
        long maxFileBytes,
        int maxOutputTokens,
        String thinkingLevel,
        Duration timeout,
        int dailyCallLimit,
        int retryCallReserve,
        int maxContinuationCalls,
        int continuationMinExpectedUnits,
        String cacheDirectory,
        int maxDocumentsPerRequest,
        int maxPagesPerRequest
) {
    public GeminiPdfParserProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank()
                ? "https://generativelanguage.googleapis.com" : baseUrl.trim();
        model = model == null || model.isBlank() ? "gemini-3.5-flash-lite" : model;
        maxFileBytes = maxFileBytes <= 0 ? 20 * 1024 * 1024L : maxFileBytes;
        maxOutputTokens = maxOutputTokens <= 0 ? 65_536 : Math.min(maxOutputTokens, 65_536);
        thinkingLevel = thinkingLevel == null || thinkingLevel.isBlank() ? "medium" : thinkingLevel;
        timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
        dailyCallLimit = dailyCallLimit <= 0 ? 500 : dailyCallLimit;
        retryCallReserve = retryCallReserve < 0 ? 0 : Math.min(retryCallReserve, dailyCallLimit);
        maxContinuationCalls = maxContinuationCalls < 0 ? 0 : maxContinuationCalls;
        continuationMinExpectedUnits = continuationMinExpectedUnits <= 0 ? 50 : continuationMinExpectedUnits;
        cacheDirectory = cacheDirectory == null || cacheDirectory.isBlank()
                ? ".gemini-pdf-cache" : cacheDirectory;
        maxDocumentsPerRequest = maxDocumentsPerRequest <= 0 ? 1 : maxDocumentsPerRequest;
        maxPagesPerRequest = maxPagesPerRequest <= 0 ? 120 : Math.min(maxPagesPerRequest, 1_000);
    }

    public Path cachePath() {
        return Path.of(cacheDirectory).toAbsolutePath().normalize();
    }

    public boolean isConfigured() {
        return enabled && key != null && !key.isBlank() && !key.startsWith("YOUR_");
    }
}
