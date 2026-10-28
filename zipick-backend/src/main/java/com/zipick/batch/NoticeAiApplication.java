package com.zipick.batch;

import com.zipick.ZipickApplication;
import com.zipick.external.ai.NoticeAiEnrichmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;

/** 기본 공고 수집과 분리하여 첨부문서 AI 보완만 한 번 실행합니다. */
public final class NoticeAiApplication {

    private static final Logger log = LoggerFactory.getLogger(NoticeAiApplication.class);

    private NoticeAiApplication() {
    }

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(ZipickApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLazyInitialization(true);

        try (LocalCollectorObservation observation = LocalCollectorObservation.start("ai");
             ConfigurableApplicationContext context = application.run(withAiEnabled(args))) {
            if (observation != null) observation.attachAfterStartup();
            NoticeAiEnrichmentService service = context.getBean(NoticeAiEnrichmentService.class);
            NoticeAiEnrichmentService.Result result = service.enrichExistingNotices();
            log.info("AI 보조 수집 완료: 대상={}, 처리={}, 상세주택 반영={}, 실패={}, 정규화 후보={}",
                    result.candidates(), result.processed(), result.detailed(), result.failed(),
                    result.normalizationCandidates());
            // AI 보완으로 상세 주택이 새로 생길 수 있어 주변 인프라를 한 번 더 채운다 (대상이 없으면 SELECT 한 번)
            InfraCollectionStep.run(context, "AI 보완 후");
            if (observation != null) observation.success();
        }
    }

    static String[] withAiEnabled(String[] args) {
        List<String> effective = new ArrayList<>();
        if (args != null) {
            for (String arg : args) {
                if (!arg.startsWith("--zipick.gemini-pdf-parser.enabled=")
                        && !arg.startsWith("--zipick.gemini-normalization.enabled=")) {
                    effective.add(arg);
                }
            }
        }
        effective.add("--zipick.gemini-pdf-parser.enabled=true");
        effective.add("--zipick.gemini-normalization.enabled=true");
        return effective.toArray(String[]::new);
    }
}
