package com.zipick.applicationguide.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Gemini generateContent 응답 중 우리가 쓰는 부분만.
 *
 * 응답 스키마는 구글 쪽에서 필드가 늘어날 수 있으므로 모르는 필드는 무시한다.
 * 여기에 없는 필드가 추가됐다고 해서 기능이 깨지면 안 된다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiResponse(
        List<Candidate> candidates,
        UsageMetadata usageMetadata,
        PromptFeedback promptFeedback
) {

    /** 모델이 만들어 낸 JSON 문자열. 없으면 null */
    public String text() {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        Candidate candidate = candidates.getFirst();
        if (candidate.content() == null || candidate.content().parts() == null) {
            return null;
        }
        String joined = candidate.content().parts().stream()
                .map(Part::text)
                .filter(part -> part != null && !part.isBlank())
                .reduce("", String::concat);
        return joined.isBlank() ? null : joined;
    }

    public String finishReason() {
        return candidates == null || candidates.isEmpty() ? null : candidates.getFirst().finishReason();
    }

    /** 안전 필터 등으로 요청 자체가 막혔는지 */
    public String blockReason() {
        return promptFeedback == null ? null : promptFeedback.blockReason();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidate(Content content, String finishReason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Content(List<Part> parts, String role) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Part(String text) {}

    /**
     * 토큰 사용량. 무료 사용량을 쓰는 기능이라 로그로 남겨 두고 실제 사용량을 보며 정책을 정한다.
     *
     * @param thoughtsTokenCount 생각(thinking) 토큰. 모델에 따라 없을 수 있다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UsageMetadata(
            Integer promptTokenCount,
            Integer candidatesTokenCount,
            Integer thoughtsTokenCount,
            Integer totalTokenCount
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PromptFeedback(String blockReason) {}
}
