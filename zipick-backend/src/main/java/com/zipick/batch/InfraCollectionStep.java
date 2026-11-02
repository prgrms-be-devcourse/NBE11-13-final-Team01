package com.zipick.batch;

import com.zipick.infrastructure.service.HousingInfraCollectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.util.Map;
import java.util.Optional;

/**
 * 수집기 진입점이 공고 처리 직후에 주변 인프라 수집을 이어서 실행한다.
 *
 * 좌표는 공고 수집의 마지막 단계에서 생기므로 같은 프로세스에서 바로 돌리면 새 주택을 그 자리에서 채운다.
 * 웹 서버는 운영에서 인프라 수집을 끄므로(INFRA_COLLECTION_ENABLED=false) 이 단계가 운영의 수집 경로다.
 * 실패해도 이미 끝난 공고 수집 결과는 그대로 두고, 남은 주택은 다음 실행에서 다시 대상이 된다.
 */
final class InfraCollectionStep {

    private static final Logger log = LoggerFactory.getLogger(InfraCollectionStep.class);

    private InfraCollectionStep() {
    }

    /** 실행 기록(collection_run.summary)에 넣을 요약. 꺼져 있거나 실패하면 빈 Optional */
    static Optional<HousingInfraCollectionService.CollectionSummary> run(ApplicationContext context, String trigger) {
        try {
            return context.getBean(HousingInfraCollectionService.class).collect(trigger);
        } catch (RuntimeException e) {
            log.warn("주변 인프라 수집을 실행하지 못했습니다: {}", e.toString());
            return Optional.empty();
        }
    }

    static Map<String, Object> summarize(Optional<HousingInfraCollectionService.CollectionSummary> summary) {
        return summary.<Map<String, Object>>map(s -> Map.of(
                "targets", s.targets(), "saved", s.saved(), "dataErrors", s.dataErrors(),
                "apiErrors", s.apiErrors(), "apiCalls", s.apiCalls(), "aborted", s.aborted()
        )).orElse(Map.of("skipped", true));
    }
}
