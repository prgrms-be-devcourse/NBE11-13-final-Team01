package com.zipick.external.ai;

public record GeminiTokenUsage(
        long promptTokens,
        long candidateTokens,
        long thoughtTokens,
        long totalTokens
) {
    public GeminiTokenUsage plus(GeminiTokenUsage other) {
        return new GeminiTokenUsage(
                promptTokens + other.promptTokens,
                candidateTokens + other.candidateTokens,
                thoughtTokens + other.thoughtTokens,
                totalTokens + other.totalTokens
        );
    }

    public static GeminiTokenUsage empty() {
        return new GeminiTokenUsage(0, 0, 0, 0);
    }
}
