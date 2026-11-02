package com.zipick.batch;

import com.zipick.ZipickApplication;
import com.zipick.admin.collection.CollectionJobType;
import com.zipick.admin.collection.CollectionRun;
import com.zipick.admin.collection.CollectionRunRecorder;
import com.zipick.notice.dto.NoticeSyncResponse;
import com.zipick.notice.service.MyHomeSyncService;
import com.zipick.notification.service.CollectorReportNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 공고 수집을 한 번 실행하는 별도 진입점이다. 운영에서는 compose 의 collector 서비스로, 로컬에서는 IntelliJ 에서 실행한다.
 * 웹 서버는 시작하지 않으며, 공고 수집 → 주변 인프라 수집 → 일일 보고 순으로 끝나면 애플리케이션도 종료한다.
 */
public final class NoticeCollectionApplication {

    private static final Logger log = LoggerFactory.getLogger(NoticeCollectionApplication.class);

    private NoticeCollectionApplication() {
    }

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(ZipickApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        // 웹 전용 SecurityFilterChain 같은 빈은 수집 실행에서 생성하지 않는다.
        application.setLazyInitialization(true);

        try (LocalCollectorObservation observation = LocalCollectorObservation.start("basic");
             ConfigurableApplicationContext context = application.run(withAiDisabled(args))) {
            if (observation != null) observation.attachAfterStartup();
            // 운영자 화면의 수집 현황은 이 기록(collection_run)을 본다. 기록 실패는 수집을 멈추지 않는다
            // 테스트의 가짜 컨텍스트는 이 빈을 주지 않으므로 null 이면 기록 없이 진행한다
            CollectionRunRecorder recorder = context.getBean(CollectionRunRecorder.class);
            CollectionRun run = recorder == null ? null : recorder.start(CollectionJobType.BASIC);
            Map<String, Object> summary = CollectionRunRecorder.summary();
            try {
            MyHomeSyncService syncService = context.getBean(MyHomeSyncService.class);
            NoticeSyncResponse result = syncService.synchronize();
            // elapsedSeconds 는 synchronize() 전체(수집 → 저장 → 정규화 → 좌표 보완) 시간이다.
            log.info(
                    "공고 수집 완료: 원본 행={}, 신규 공고={}, 갱신 공고={}, 주택={}, 공급정보={}, 목록 소실 마감 공고={}, 비활성 주택={}, 비활성 공급정보={}, elapsedSeconds={}",
                    result.collectedRows(),
                    result.createdNotices(),
                    result.updatedNotices(),
                    result.synchronizedHousings(),
                    result.synchronizedSupplies(),
                    result.missingClosedNotices(),
                    result.inactiveHousings(),
                    result.inactiveSupplies(),
                    Duration.between(result.startedAt(), result.finishedAt()).toSeconds()
            );
            summary.put("collectedRows", result.collectedRows());
            summary.put("createdNotices", result.createdNotices());
            summary.put("updatedNotices", result.updatedNotices());
            summary.put("housings", result.synchronizedHousings());
            summary.put("supplies", result.synchronizedSupplies());
            summary.put("missingClosedNotices", result.missingClosedNotices());
            summary.put("inactiveHousings", result.inactiveHousings());
            summary.put("elapsedSeconds", Duration.between(result.startedAt(), result.finishedAt()).toSeconds());
            // 새로 좌표가 생긴 주택의 주변 인프라를 이어서 채운다 (웹 서버는 운영에서 이 수집을 하지 않는다)
            summary.put("infra", InfraCollectionStep.summarize(InfraCollectionStep.run(context, "공고 수집 후")));
            // 운영 슬랙·메일로 일일 보고 (알림센터 스펙 견본). 받을 곳이 설정돼 있지 않으면 건너뛴다.
            // 보고가 실패해도 수집은 이미 끝났으므로 수집 결과를 실패로 만들지 않는다
            try {
                if (context.getBean(CollectorReportNotifier.class).report(result)) log.info("수집 보고를 알림센터에 요청했습니다.");
            } catch (RuntimeException e) {
                log.warn("수집 보고를 보내지 못했습니다: {}", e.toString());
            }
            if (recorder != null) recorder.success(run, summary);
            } catch (RuntimeException e) {
                if (recorder != null) recorder.fail(run, e, summary);
                throw e;
            }
            if (observation != null) observation.success();
        }
    }

    static String[] withAiDisabled(String[] args) {
        List<String> effective = new ArrayList<>();
        if (args != null) {
            for (String arg : args) {
                if (!arg.startsWith("--zipick.gemini-pdf-parser.enabled=")
                        && !arg.startsWith("--zipick.gemini-normalization.enabled=")) effective.add(arg);
            }
        }
        effective.add("--zipick.gemini-pdf-parser.enabled=false");
        effective.add("--zipick.gemini-normalization.enabled=false");
        return effective.toArray(String[]::new);
    }
}
