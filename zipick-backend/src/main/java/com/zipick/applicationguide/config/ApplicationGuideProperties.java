package com.zipick.applicationguide.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 신청 준비 가이드 설정. (application.yml 의 zipick.application-guide)
 *
 * <b>이 기능이 Gemini 를 어떻게 쓸지에 대한 정책을 전부 여기에 모은다</b> — 모델, 타임아웃,
 * 하루 호출 상한, 문서 축약 기준. 공통 {@link GeminiProperties} 에는 키와 주소만 둔다.
 * 같은 Gemini 를 쓰는 다른 기능(공고 수집 보조 PDF 파서)이 모델이나 타임아웃을 바꿔도
 * 이 기능의 동작이 따라 바뀌지 않게 하기 위해서다.
 *
 * 문서 선별·축약 기준을 코드가 아닌 설정으로 두어, 실제 공고를 보고 조정할 때
 * 재배포 없이 값만 바꿀 수 있게 한다.
 */
@ConfigurationProperties(prefix = "zipick.application-guide")
public record ApplicationGuideProperties(
        /**
         * 이 기능이 쓸 Gemini 모델. 코드에 박지 않는 이유는 모델 정책이 바뀌면 갈아 끼워야 하기 때문이다.
         * 다른 AI 기능과 같은 모델을 써야 할 의무는 없다.
         */
        String model,
        /** TCP·TLS 연결까지. 보통 수백 ms 안에 끝난다 */
        Duration connectTimeout,
        /**
         * 응답을 기다리는 시간. 공고문 수십 쪽을 읽고 구조화된 JSON 을 만들어 내는 호출이라 넉넉히 둔다.
         * 짧게 잡으면 정상 응답을 실패로 오해하고, 사용자는 사용량만 쓰고 결과를 못 받는다.
         * (PDF 원본을 통째로 보내는 기능은 이보다 훨씬 길어야 한다 — 그래서 공유하지 않는다)
         */
        Duration readTimeout,
        /**
         * Gemini 에 보낼 공고문 텍스트 최대 길이(자). 모델 한계가 아니라 토큰 절약이 목적이다.
         *
         * 기본값을 30,000 에서 60,000 으로 올렸다. 실제 장문 공고(GH 경기행복주택 108쪽, 20만 자)로 재 보니
         * 30,000 에서는 신청 준비 관련 47쪽 중 18쪽만 들어가고, 점수가 높은 자격 쪽이 "예산에 안 맞아"
         * 건너뛰어진 뒤 더 작고 점수 낮은 쪽이 대신 들어갔다. 60,000 에서는 그 쪽들이 모두 들어간다.
         * 전문(20만 자)을 보내자는 것이 아니라 선별 결과를 담을 여유를 준 값이다.
         */
        int maxInputChars,
        /**
         * 한 공고에서 공고문 후보를 몇 건까지 추릴지. 실제 분석은 그중 점수가 가장 높은 1건만 한다.
         * 한국 모집공고는 보통 공고문 한 파일에 자격·서류·절차가 모두 들어 있어,
         * 여러 파일을 합치면 토큰만 늘고 내용이 겹친다.
         */
        int maxDocuments,
        /** 관련 페이지의 앞뒤 몇 페이지까지 함께 보낼지. 표가 페이지를 넘어가는 경우 때문에 필요하다 */
        int neighborPages,
        /** 추출된 글자가 이보다 적으면 스캔 이미지 문서로 보고 포기한다 */
        int minExtractedChars,
        /**
         * 이 기능이 하루에 허용하는 생성 호출 수. 설정이 없으면 기본값 200 이 적용되고,
         * <b>0 이하를 주면 제한하지 않는다.</b>
         *
         * 기본값을 설정 파일이 아니라 여기에 둔 이유가 있다. 운영에서는 docker compose 가
         * .env 의 빈 값도 환경변수로 그대로 넘기는데, 빈 문자열은 Spring 에서 null 로 바인딩돼
         * application.yml 의 placeholder 기본값(`:200`)을 건너뛴다. 그러면 .env 를 비워 둔
         * 운영 환경만 조용히 무제한이 돼 버린다. 기본값을 코드에 두면 로컬·Docker·CD 가 같아진다.
         *
         * <b>Google 의 프로젝트·모델 quota 와는 별개인 기능 자체의 보호장치다.</b>
         * Gemini 사용량은 Google 프로젝트와 사용 모델 구성에 따라 다른 AI 기능과 공유될 수 있으므로,
         * 운영에서는 함께 쓰는 기능의 호출량을 고려해 조정해야 한다.
         *
         * 분당 제어는 코드에 두지 않았다. 요청 하나의 입력 토큰이 공고문 길이에 따라 달라져
         * "분당 몇 건" 을 고정할 수 없기 때문이다. 사용자가 직접 눌러야만 호출되는 구조와
         * 429 처리에 맡긴다.
         */
        Integer dailyCallBudget
) {
    private static final String DEFAULT_MODEL = "gemini-3.5-flash-lite";
    private static final int DEFAULT_DAILY_CALL_BUDGET = 200;

    public ApplicationGuideProperties {
        model = hasText(model) ? model.trim() : DEFAULT_MODEL;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(90) : readTimeout;
        maxInputChars = maxInputChars <= 0 ? 60_000 : maxInputChars;
        maxDocuments = maxDocuments <= 0 ? 1 : maxDocuments;
        neighborPages = neighborPages < 0 ? 1 : neighborPages;
        minExtractedChars = minExtractedChars <= 0 ? 1_000 : minExtractedChars;
        dailyCallBudget = dailyCallBudget == null ? DEFAULT_DAILY_CALL_BUDGET : dailyCallBudget;
    }

    /** 생성자에서 null 을 기본값으로 바꾸므로, 여기서는 0 이하(= 제한 없음)만 가려내면 된다 */
    public boolean hasDailyBudget() {
        return dailyCallBudget > 0;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
