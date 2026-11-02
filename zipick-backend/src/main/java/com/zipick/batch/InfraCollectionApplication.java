package com.zipick.batch;

import com.zipick.ZipickApplication;
import com.zipick.admin.collection.CollectionJobType;
import com.zipick.admin.collection.CollectionRun;
import com.zipick.admin.collection.CollectionRunRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;

/**
 * 주변 인프라 수집만 한 번 실행하는 진입점. 운영자 화면에서 "인프라만 다시" 를 요청했을 때
 * run-requested-collection.sh 가 collector 컨테이너로 띄운다. 공고·AI 수집은 하지 않는다.
 */
public final class InfraCollectionApplication {

    private static final Logger log = LoggerFactory.getLogger(InfraCollectionApplication.class);

    private InfraCollectionApplication() {
    }

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(ZipickApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLazyInitialization(true);

        try (ConfigurableApplicationContext context = application.run(NoticeCollectionApplication.withAiDisabled(args))) {
            CollectionRunRecorder recorder = context.getBean(CollectionRunRecorder.class);
            CollectionRun run = recorder == null ? null : recorder.start(CollectionJobType.INFRA);
            try {
                Map<String, Object> infra = InfraCollectionStep.summarize(InfraCollectionStep.run(context, "운영자 요청"));
                log.info("주변 인프라 단독 수집 완료: {}", infra);
                if (recorder != null) recorder.success(run, Map.of("infra", infra));
            } catch (RuntimeException e) {
                if (recorder != null) recorder.fail(run, e, Map.of());
                throw e;
            }
        }
    }
}
