package com.zipick.batch;

import com.zipick.ZipickApplication;
import com.zipick.notice.dto.NoticeSyncResponse;
import com.zipick.notice.service.MyHomeSyncService;
import com.zipick.notification.service.CollectorReportNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * IntelliJ에서 공고 수집만 한 번 실행하기 위한 별도 진입점이다.
 * 웹 서버는 시작하지 않으며, 수집이 끝나면 애플리케이션도 종료한다.
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

        try (ConfigurableApplicationContext context = application.run(args)) {
            MyHomeSyncService syncService = context.getBean(MyHomeSyncService.class);
            NoticeSyncResponse result = syncService.synchronize();
            log.info(
                    "공고 수집 완료: 원본 행={}, 신규 공고={}, 갱신 공고={}, 주택={}, 공급정보={}, 목록 소실 마감 공고={}, 비활성 주택={}, 비활성 공급정보={}",
                    result.collectedRows(),
                    result.createdNotices(),
                    result.updatedNotices(),
                    result.synchronizedHousings(),
                    result.synchronizedSupplies(),
                    result.missingClosedNotices(),
                    result.inactiveHousings(),
                    result.inactiveSupplies()
            );
            // 운영 슬랙·메일로 일일 보고 (알림센터 스펙 견본). 받을 곳이 설정돼 있지 않으면 건너뛴다.
            // 보고가 실패해도 수집은 이미 끝났으므로 수집 결과를 실패로 만들지 않는다
            try {
                if (context.getBean(CollectorReportNotifier.class).report(result)) log.info("수집 보고를 알림센터에 요청했습니다.");
            } catch (RuntimeException e) {
                log.warn("수집 보고를 보내지 못했습니다: {}", e.toString());
            }
        }
    }
}
