package com.zipick.external.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.Metrics;

final class GeminiHttpTransport implements GeminiApiTransport {
    private static final Logger log = LoggerFactory.getLogger(GeminiHttpTransport.class);

    private final String apiRoot;
    private final String key;
    private final String model;
    private final String stage;
    private final java.time.Duration timeout;
    private final HttpClient client;

    GeminiHttpTransport(GeminiPdfParserProperties properties) {
        this.apiRoot = apiRoot(properties.baseUrl());
        this.key = properties.key();
        this.model = properties.model();
        this.stage = "extraction";
        this.timeout = properties.timeout();
        this.client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
    }

    GeminiHttpTransport(String baseUrl, String key, String model, java.time.Duration timeout) {
        this.apiRoot = apiRoot(baseUrl);
        this.key = key;
        this.model = model;
        this.stage = "normalization";
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
    }

    @Override
    public String generate(String requestBody) {
        long started = System.nanoTime();
        String outcome = "error";
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        apiRoot + model + ":generateContent"
                ))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", key)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                Metrics.counter("zipick.external.api.calls", "provider", "gemini", "stage", stage, "outcome", "error").increment();
                log.warn("COLLECTOR_METRIC event=gemini_http_error stage={} status={} model={}", stage, response.statusCode(), model);
                throw new IllegalStateException("Gemini PDF 분석 실패: status=" + response.statusCode()
                        + ", body=" + abbreviate(response.body()));
            }
            Metrics.counter("zipick.external.api.calls", "provider", "gemini", "stage", stage, "outcome", "success").increment();
            log.info("COLLECTOR_METRIC event=gemini_http_success stage={} model={}", stage, model);
            outcome = "success";
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            Metrics.counter("zipick.external.api.calls", "provider", "gemini", "stage", stage, "outcome", "error").increment();
            log.warn("COLLECTOR_METRIC event=gemini_http_error stage={} status=interrupted model={}", stage, model);
            throw new IllegalStateException("Gemini PDF 분석이 중단되었습니다.", exception);
        } catch (IOException exception) {
            Metrics.counter("zipick.external.api.calls", "provider", "gemini", "stage", stage, "outcome", "error").increment();
            log.warn("COLLECTOR_METRIC event=gemini_http_error stage={} status=network model={}", stage, model);
            throw new IllegalStateException("Gemini PDF 분석 요청에 실패했습니다.", exception);
        } finally {
            log.info("Gemini HTTP 호출 시간: 단계={}, 모델={}, 결과={}, 경과ms={}",
                    stage, model, outcome, (System.nanoTime() - started) / 1_000_000);
            Metrics.timer("zipick.external.api.duration", "provider", "gemini", "stage", stage, "outcome", outcome)
                    .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    private String abbreviate(String value) {
        if (value == null || value.length() <= 500) return value;
        return value.substring(0, 500) + "...";
    }

    private static String apiRoot(String baseUrl) {
        String normalized = baseUrl == null || baseUrl.isBlank()
                ? "https://generativelanguage.googleapis.com" : baseUrl.trim();
        return normalized.replaceAll("/+$", "") + "/v1beta/models/";
    }
}
