package com.zipick.batch;

import com.zipick.external.ai.NoticeAiEnrichmentService;
import com.zipick.infrastructure.service.HousingInfraCollectionService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NoticeAiApplicationTest {

    @Test
    void runsOnlyAiEnrichmentAndClosesContext() {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        NoticeAiEnrichmentService service = mock(NoticeAiEnrichmentService.class);
        HousingInfraCollectionService infra = mock(HousingInfraCollectionService.class);
        when(context.getBean(NoticeAiEnrichmentService.class)).thenReturn(service);
        when(context.getBean(HousingInfraCollectionService.class)).thenReturn(infra);
        when(service.enrichExistingNotices()).thenReturn(
                new NoticeAiEnrichmentService.Result(2, 2, 1, 0, 1));

        try (MockedConstruction<SpringApplication> construction = mockConstruction(
                SpringApplication.class,
                (application, ignored) -> when(application.run(any(String[].class))).thenReturn(context)
        )) {
            NoticeAiApplication.main(new String[]{"--spring.profiles.active=local"});
            assertThat(construction.constructed()).hasSize(1);
        }

        verify(service).enrichExistingNotices();
        verify(infra).collect("AI 보완 후");   // 새 상세 주택의 주변 인프라를 이어서 채운다
        verify(context).close();
    }

    @Test
    void alwaysEnablesAiStages() {
        assertThat(NoticeAiApplication.withAiEnabled(new String[]{
                "--spring.profiles.active=local",
                "--zipick.gemini-pdf-parser.enabled=false",
                "--zipick.gemini-normalization.enabled=false"
        })).containsExactly(
                "--spring.profiles.active=local",
                "--zipick.gemini-pdf-parser.enabled=true",
                "--zipick.gemini-normalization.enabled=true"
        );
    }

    @Test
    void hasNoPublicConstructor() throws Exception {
        Constructor<NoticeAiApplication> constructor = NoticeAiApplication.class.getDeclaredConstructor();
        assertThat(constructor.canAccess(null)).isFalse();
    }
}
