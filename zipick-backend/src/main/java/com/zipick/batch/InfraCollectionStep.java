package com.zipick.batch;

import com.zipick.infrastructure.service.HousingInfraCollectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

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

    static void run(ApplicationContext context, String trigger) {
        try {
            context.getBean(HousingInfraCollectionService.class).collect(trigger);
        } catch (RuntimeException e) {
            log.warn("주변 인프라 수집을 실행하지 못했습니다: {}", e.toString());
        }
    }
}
