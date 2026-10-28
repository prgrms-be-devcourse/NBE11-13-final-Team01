package com.zipick.external.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class GeminiPdfParserPropertiesBindingTest {

    private ApplicationContextRunner runner(String... environmentVariables) {
        Map<String, Object> environment = new HashMap<>();
        for (String entry : environmentVariables) {
            int separator = entry.indexOf('=');
            environment.put(entry.substring(0, separator), entry.substring(separator + 1));
        }
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new SystemEnvironmentPropertySource("test-env", environment)))
                .withUserConfiguration(TestConfig.class);
    }

    @Test
    void Gemini_연결정보는_공유하고_기능별_정책은_별도로_바인딩한다() {
        runner(
                "GEMINI_API_KEY=shared-gemini-key",
                "GEMINI_PDF_PARSER_ENABLED=true",
                "GEMINI_PDF_PARSER_MODEL=pdf-only-model",
                "GEMINI_NORMALIZATION_ENABLED=true",
                "APPLICATION_GUIDE_MODEL=guide-only-model"
        ).run(context -> {
            assertThat(context).hasNotFailed();

            GeminiPdfParserProperties parser = context.getBean(GeminiPdfParserProperties.class);
            GeminiNormalizationProperties normalization = context.getBean(GeminiNormalizationProperties.class);

            assertThat(parser.key()).isEqualTo("shared-gemini-key");
            assertThat(parser.isConfigured()).isTrue();
            assertThat(parser.baseUrl()).isEqualTo("https://generativelanguage.googleapis.com");
            assertThat(parser.enabled()).isTrue();
            assertThat(parser.model()).isEqualTo("pdf-only-model");
            assertThat(parser.timeout()).isEqualTo(Duration.ofMinutes(5));
            assertThat(parser.dailyCallLimit()).isEqualTo(500);
            assertThat(parser.continuationMinExpectedUnits()).isEqualTo(50);

            assertThat(normalization.enabled()).isTrue();
            assertThat(normalization.model()).isEqualTo("gemini-3.8-flash");
            assertThat(normalization.dailyCallLimit()).isEqualTo(20);
        });
    }

    @Test
    void 기본값은_활성화하되_API_KEY가_없으면_외부호출은_비활성화한다() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();

            GeminiPdfParserProperties parser = context.getBean(GeminiPdfParserProperties.class);
            GeminiNormalizationProperties normalization = context.getBean(GeminiNormalizationProperties.class);

            assertThat(parser.enabled()).isTrue();
            assertThat(parser.isConfigured()).isFalse();
            assertThat(normalization.enabled()).isTrue();
        });
    }

    @Configuration
    @EnableConfigurationProperties({GeminiPdfParserProperties.class, GeminiNormalizationProperties.class})
    static class TestConfig {
    }
}
