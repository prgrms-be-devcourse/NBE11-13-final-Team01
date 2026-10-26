package com.zipick.applicationguide.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gemini <b>제공자 공통</b> 설정. (gemini.api)
 *
 * 여기에는 "어느 서비스에 어떤 자격으로 접속하는가" 만 둔다. 즉 인증 키와 엔드포인트다.
 * 모델·타임아웃·호출 상한 같은 <b>정책은 기능마다 다르므로 여기에 두지 않는다.</b>
 *
 * <h3>왜 나눠 두는가</h3>
 * 팀에서 Gemini 를 쓰는 기능이 둘(이 기능, 공고 수집 보조 PDF 파서)이고 사용 방식이 다르다.
 * PDF 파서는 PDF 원본을 통째로 보내 긴 타임아웃과 큰 출력이 필요하고,
 * 이 기능은 축약한 텍스트를 보내 상대적으로 짧게 끝난다. 두 기능이 서로 다른 모델을 쓸 수도 있다.
 * 그런데 {@code gemini.api.model} 이나 {@code gemini.api.timeout} 처럼 이름이 하나뿐이면
 * 한쪽이 값을 바꾸는 순간 다른 쪽 동작까지 바뀐다. 그래서 공통 영역을 키와 주소로만 제한했다.
 *
 * 기능별 정책은 각자의 설정 아래에 둔다. 이 기능은 {@link ApplicationGuideProperties}.
 */
@ConfigurationProperties(prefix = "gemini.api")
public record GeminiProperties(
        String key,
        String baseUrl
) {
    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    public GeminiProperties {
        baseUrl = hasText(baseUrl) ? baseUrl.trim() : DEFAULT_BASE_URL;
    }

    /** 키가 없으면 AI 기능만 꺼지고 나머지 기능에는 영향이 없다 */
    public boolean isConfigured() {
        return hasText(key) && !key.startsWith("YOUR_");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
