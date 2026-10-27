package com.zipick.external.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

final class GeminiHttpTransport implements GeminiApiTransport {

    private final String apiRoot;
    private final String key;
    private final String model;
    private final java.time.Duration timeout;
    private final HttpClient client;

    GeminiHttpTransport(GeminiPdfParserProperties properties) {
        this.apiRoot = apiRoot(properties.baseUrl());
        this.key = properties.key();
        this.model = properties.model();
        this.timeout = properties.timeout();
        this.client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
    }

    GeminiHttpTransport(String baseUrl, String key, String model, java.time.Duration timeout) {
        this.apiRoot = apiRoot(baseUrl);
        this.key = key;
        this.model = model;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
    }

    @Override
    public String generate(String requestBody) {
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
                throw new IllegalStateException("Gemini PDF 분석 실패: status=" + response.statusCode()
                        + ", body=" + abbreviate(response.body()));
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Gemini PDF 분석이 중단되었습니다.", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Gemini PDF 분석 요청에 실패했습니다.", exception);
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
