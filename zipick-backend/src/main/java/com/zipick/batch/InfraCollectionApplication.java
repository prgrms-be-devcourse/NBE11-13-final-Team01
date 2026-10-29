package com.zipick.batch;

import com.zipick.ZipickApplication;
import com.zipick.infrastructure.service.HousingInfraCollectionService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 주변 인프라 수집을 한 번 실행하는 별도 진입점이다.
 *
 * 매일 수집(deploy/monitoring/run-daily-collection.sh)이 기본 수집 → AI 수집 다음 마지막에 부른다.
 * 좌표는 두 수집이 모두 만들 수 있으므로(공고 수집의 좌표 변환, AI 상세 주택 반영 뒤 좌표 보완) 둘이 끝난 뒤 한 번만 돈다.
 * 앞 단계가 실패해도 스크립트가 이 단계는 실행한다. 웹 서버는 시작하지 않으며, 수집이 끝나면 종료한다.
 * 수집 결과 로그는 HousingInfraCollectionService 가 남긴다.
 */
public final class InfraCollectionApplication {

    private InfraCollectionApplication() {
    }

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(ZipickApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLazyInitialization(true);

        try (LocalCollectorObservation observation = LocalCollectorObservation.start("infra");
             ConfigurableApplicationContext context = application.run(args)) {
            if (observation != null) observation.attachAfterStartup();
            context.getBean(HousingInfraCollectionService.class).collect("일일 수집");
            if (observation != null) observation.success();
        }
    }
}
