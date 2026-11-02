package com.zipick.applicationguide.client;

/**
 * Gemini 한 번 호출의 결과.
 *
 * @param json       모델이 돌려준 JSON 문자열 (아직 DTO 로 바꾸기 전)
 * @param usage      토큰 사용량. 응답에 없으면 null
 * @param elapsedMs  요청부터 응답까지 걸린 시간
 */
public record GeminiCompletion(String json, GeminiResponse.UsageMetadata usage, long elapsedMs) {

    /** 로그에 남길 한 줄 요약. 무료 사용량을 보며 정책을 정하는 데 쓴다 */
    public String usageSummary() {
        if (usage == null) {
            return "usage 없음, " + elapsedMs + "ms";
        }
        return "input=" + usage.promptTokenCount()
                + ", output=" + usage.candidatesTokenCount()
                + (usage.thoughtsTokenCount() == null ? "" : ", thinking=" + usage.thoughtsTokenCount())
                + ", total=" + usage.totalTokenCount()
                + ", " + elapsedMs + "ms";
    }
}
