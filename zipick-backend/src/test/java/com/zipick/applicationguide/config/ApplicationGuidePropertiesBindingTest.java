package com.zipick.applicationguide.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * 실제 application.yml 을 읽어 설정 바인딩을 확인한다.
 *
 * 여기서 꼭 잡고 싶은 것은 <b>모델과 호출 상한이 기능별로 분리돼 있다</b>는 점이다.
 * 공통 gemini.api 에는 키·주소만 두고, 모델·타임아웃·상한은 zipick.application-guide 아래에 둔다.
 * 같은 Gemini 를 쓰는 다른 기능이 값을 바꿔도 이 기능이 따라 바뀌면 안 된다.
 *
 * 환경변수 우선순위도 중첩 placeholder 로 표현돼 있어 눈으로는 확인이 어렵다. 테스트로 고정한다.
 */
class ApplicationGuidePropertiesBindingTest {

    /**
     * 환경변수를 OS 환경변수와 같은 방식으로 넣는다.
     *
     * withPropertyValues 로는 부족하다. 대문자·밑줄 이름을 설정 키에 맞춰 주는 relaxed binding 은
     * SystemEnvironmentPropertySource 에만 적용되기 때문이다 (실제로 테스트가 그렇게 실패했다).
     * GEMINI_API_KEY → gemini.api.key 매핑을 확인하려면 이 방식이어야 한다.
     */
    private ApplicationContextRunner runner(String... environmentVariables) {
        Map<String, Object> env = new HashMap<>();
        for (String entry : environmentVariables) {
            int split = entry.indexOf('=');
            env.put(entry.substring(0, split), entry.substring(split + 1));
        }
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new SystemEnvironmentPropertySource("test-env", env)))
                .withUserConfiguration(TestConfig.class);
    }

    // ---------- 공통 / 기능별 분리 ----------

    @Test
    @DisplayName("공통 gemini.api 에는 키와 주소만 있다 (모델·타임아웃은 없다)")
    void commonPropertiesHoldOnlyKeyAndBaseUrl() {
        runner().run(context -> {
            GeminiProperties gemini = context.getBean(GeminiProperties.class);

            assertThat(gemini.baseUrl()).isEqualTo("https://generativelanguage.googleapis.com");
            // application.yml 에 key 를 적지 않으므로, 환경변수·비밀 파일이 없으면 비어 있다
            assertThat(gemini.isConfigured()).isFalse();
        });
    }

    @Test
    @DisplayName("모델·타임아웃·호출 상한은 zipick.application-guide 에 바인딩된다")
    void featurePropertiesHoldPolicy() {
        runner().run(context -> {
            ApplicationGuideProperties guide = context.getBean(ApplicationGuideProperties.class);

            assertThat(guide.model()).isEqualTo("gemini-3.5-flash-lite");
            assertThat(guide.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(guide.readTimeout()).isEqualTo(Duration.ofSeconds(90));
            assertThat(guide.dailyCallBudget()).isEqualTo(200);
            assertThat(guide.maxInputChars()).isEqualTo(60_000);
            assertThat(guide.maxDocuments()).isEqualTo(1);
            assertThat(guide.neighborPages()).isEqualTo(1);
            assertThat(guide.minExtractedChars()).isEqualTo(1_000);
        });
    }

    // ---------- 모델 우선순위 ----------

    @Test
    @DisplayName("환경변수가 없으면 기본 모델을 쓴다")
    void modelFallsBackToDefault() {
        runner().run(context ->
                assertThat(context.getBean(ApplicationGuideProperties.class).model())
                        .isEqualTo("gemini-3.5-flash-lite"));
    }

    @Test
    @DisplayName("GEMINI_MODEL 을 주면 팀 공통 기본 모델로 쓴다")
    void modelFallsBackToTeamWideVariable() {
        runner("GEMINI_MODEL=team-wide-model").run(context ->
                assertThat(context.getBean(ApplicationGuideProperties.class).model())
                        .isEqualTo("team-wide-model"));
    }

    @Test
    @DisplayName("APPLICATION_GUIDE_MODEL 이 GEMINI_MODEL 보다 우선한다")
    void featureModelOverridesTeamWideVariable() {
        // 다른 AI 기능이 GEMINI_MODEL 을 바꿔도 이 기능은 자기 모델을 유지할 수 있어야 한다
        runner("GEMINI_MODEL=team-wide-model", "APPLICATION_GUIDE_MODEL=guide-only-model").run(context ->
                assertThat(context.getBean(ApplicationGuideProperties.class).model())
                        .isEqualTo("guide-only-model"));
    }

    // ---------- 호출 상한 ----------

    @Test
    @DisplayName("APPLICATION_GUIDE_DAILY_CALL_BUDGET 으로 상한을 덮어쓸 수 있다")
    void dailyBudgetCanBeOverridden() {
        runner("APPLICATION_GUIDE_DAILY_CALL_BUDGET=450").run(context -> {
            ApplicationGuideProperties guide = context.getBean(ApplicationGuideProperties.class);

            assertThat(guide.dailyCallBudget()).isEqualTo(450);
            assertThat(guide.hasDailyBudget()).isTrue();
        });
    }

    @Test
    @DisplayName("환경변수를 비워 둬도 기본 상한 200 이 걸린다")
    void emptyBudgetVariableStillFallsBackToDefault() {
        // 운영에서 실제로 이렇게 들어온다. docker compose 가 .env 의 빈 값도 환경변수로 넘기는데,
        // 빈 문자열은 null 로 바인딩돼 yml placeholder 기본값을 건너뛴다.
        // 기본값을 ApplicationGuideProperties 에 둔 이유이고, 여기서 그걸 고정한다.
        runner("APPLICATION_GUIDE_DAILY_CALL_BUDGET=", "APPLICATION_GUIDE_MODEL=").run(context -> {
            ApplicationGuideProperties guide = context.getBean(ApplicationGuideProperties.class);

            assertThat(guide.dailyCallBudget()).isEqualTo(200);
            assertThat(guide.hasDailyBudget()).isTrue();
            assertThat(guide.model()).isEqualTo("gemini-3.5-flash-lite");
        });
    }

    @Test
    @DisplayName("상한을 없애려면 0 을 명시해야 한다")
    void zeroDisablesTheDailyBudget() {
        runner("APPLICATION_GUIDE_DAILY_CALL_BUDGET=0").run(context ->
                assertThat(context.getBean(ApplicationGuideProperties.class).hasDailyBudget()).isFalse());
    }

    // ---------- 인증 키 ----------

    @Test
    @DisplayName("GEMINI_API_KEY 환경변수가 gemini.api.key 로 들어온다")
    void apiKeyBindsFromEnvironmentVariableName() {
        // application.yml 에 key 를 적지 않는 이유가 여기 있다.
        // ${GEMINI_API_KEY:} 로 적어 두면 환경변수가 없을 때 빈 문자열이 확정돼
        // application-secret.yml 의 값을 덮어쓴다. 적지 않으면 relaxed binding 이 알아서 채운다.
        runner("GEMINI_API_KEY=key-from-environment").run(context -> {
            GeminiProperties gemini = context.getBean(GeminiProperties.class);

            assertThat(gemini.key()).isEqualTo("key-from-environment");
            assertThat(gemini.isConfigured()).isTrue();
        });
    }

    @Test
    @DisplayName("예제 파일의 placeholder 값은 설정된 것으로 보지 않는다")
    void placeholderKeyIsNotConsideredConfigured() {
        runner("GEMINI_API_KEY=YOUR_GEMINI_API_KEY").run(context ->
                assertThat(context.getBean(GeminiProperties.class).isConfigured()).isFalse());
    }

    @Configuration
    @EnableConfigurationProperties({GeminiProperties.class, ApplicationGuideProperties.class})
    static class TestConfig {
    }
}
