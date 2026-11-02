package com.zipick.applicationguide.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * Gemini generateContent 요청 본문.
 *
 * Google 의 Java SDK 를 쓰지 않고 REST 로 직접 부른다. 팀의 다른 AI 기능(공고 수집 보조 파서)도
 * 같은 방식이라 연결 방식을 맞춰 두었고, 새 의존성을 추가하지 않아도 된다.
 * 필요한 필드만 담았다. 비어 있는 필드는 보내지 않는다(NON_NULL).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeminiRequest(
        Content systemInstruction,
        List<Content> contents,
        GenerationConfig generationConfig
) {

    public static GeminiRequest of(String systemInstruction, String userText,
                                   Map<String, Object> responseJsonSchema, double temperature) {
        return new GeminiRequest(
                Content.text(null, systemInstruction),
                List.of(Content.text("user", userText)),
                new GenerationConfig("application/json", responseJsonSchema, temperature)
        );
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Content(String role, List<Part> parts) {
        static Content text(String role, String text) {
            return new Content(role, List.of(new Part(text)));
        }
    }

    public record Part(String text) {}

    /**
     * 자유 텍스트 대신 JSON 스키마를 강제한다.
     * 답을 받아서 다시 파싱하는 방식보다 구조가 어긋날 확률이 훨씬 낮다.
     *
     * @param temperature 공고문에 있는 사실만 정리하는 작업이라 낮게 둔다. 창의성이 필요한 일이 아니다
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GenerationConfig(
            String responseMimeType,
            Map<String, Object> responseJsonSchema,
            Double temperature
    ) {}
}
