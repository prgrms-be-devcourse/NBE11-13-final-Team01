package com.zipick.infrastructure.service;

import com.zipick.external.kakao.config.KakaoLocalApiProperties;
import com.zipick.infrastructure.config.InfraPolicy;
import com.zipick.infrastructure.config.InfraProperties;
import com.zipick.notice.domain.Housing;
import com.zipick.housing.dto.HouseFilter;
import com.zipick.infrastructure.repository.HousingInfraSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 주변 인프라를 주기적으로 미리 채워 두는 배치.
 *
 * 사용자 조회 경로에서는 외부 API 를 호출하지 않는다. 지도 상세와 (향후) 추천 모두
 * 여기서 저장해 둔 DB 데이터를 읽는다. 그래서 조회 응답이 카카오 지연에 묶이지 않는다.
 *
 * 수집 대상은 '스냅샷 없음 / TTL 만료 / 정책 변경 / 좌표 변경' 네 가지이며 쿼리 하나로 찾는다.
 * 실패한 주택은 저장되지 않으므로 다음 주기에 자동으로 다시 대상이 된다. 별도 재시도 장치가 없다.
 *
 * 웹 애플리케이션에서만 동작한다(InfraConfig 의 @ConditionalOnWebApplication).
 * 공고 수집 전용 진입점(NoticeCollectionApplication)에서는 실행되지 않는다.
 */
@Component
@ConditionalOnWebApplication
public class HousingInfraCollectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(HousingInfraCollectionScheduler.class);

    /** 단일 인스턴스 기준 중복 실행 방지. 이전 회차가 길어져도 겹쳐 돌지 않는다. */
    private final ReentrantLock lock = new ReentrantLock();

    private final HousingInfraSnapshotRepository snapshotRepository;
    private final HousingInfraCollector collector;
    private final HousingInfraPersistenceService persistenceService;
    private final InfraProperties properties;
    private final InfraPolicy policy;
    private final KakaoLocalApiProperties kakaoProperties;

    private boolean missingKeyLogged;

    public HousingInfraCollectionScheduler(
            HousingInfraSnapshotRepository snapshotRepository,
            HousingInfraCollector collector,
            HousingInfraPersistenceService persistenceService,
            InfraProperties properties,
            InfraPolicy policy,
            KakaoLocalApiProperties kakaoProperties
    ) {
        this.snapshotRepository = snapshotRepository;
        this.collector = collector;
        this.persistenceService = persistenceService;
        this.properties = properties;
        this.policy = policy;
        this.kakaoProperties = kakaoProperties;
    }

    /**
     * fixedDelay 라 이전 실행이 끝난 뒤부터 간격을 센다. 실행이 길어져도 겹치지 않는다.
     * 값은 밀리초다. 기본값은 initialDelay 10초, 주기 5분.
     */
    @Scheduled(
            fixedDelayString = "${zipick.infra.collection.interval-ms:300000}",
            initialDelayString = "${zipick.infra.collection.initial-delay-ms:10000}"
    )
    public void collectPendingHousings() {
        if (!properties.collection().enabled()) {
            return;
        }
        if (!kakaoProperties.isConfigured()) {
            if (!missingKeyLogged) {
                log.warn("카카오 REST API 키가 없어 주변 인프라 수집을 건너뜁니다.");
                missingKeyLogged = true;
            }
            return;
        }
        if (!lock.tryLock()) {
            log.debug("이전 주변 인프라 수집이 아직 진행 중이라 이번 회차를 건너뜁니다.");
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException exception) {
            // 스케줄러 스레드가 죽지 않도록 여기서 막는다. 남은 대상은 다음 주기에 다시 처리된다.
            log.error("주변 인프라 수집 중 예상치 못한 오류가 발생했습니다.", exception);
        } finally {
            lock.unlock();
        }
    }

    CollectionSummary runOnce() {
        Instant now = Instant.now();
        Instant ttlThreshold = now.minus(properties.cacheTtl());
        // 추천 후보와 같은 상태 집합을 쓴다. 추천 쪽에서 바뀌면 수집 범위도 자동으로 따라간다.
        List<Housing> targets = snapshotRepository.findCollectionTargets(
                HouseFilter.OPEN_STATUSES,
                ttlThreshold,
                policy.signature(),
                PageRequest.of(0, properties.collection().batchSize())
        );
        if (targets.isEmpty()) {
            return new CollectionSummary(0, 0, 0, 0, 0, false);
        }

        int saved = 0;
        int dataErrors = 0;
        int apiErrors = 0;
        int apiCalls = 0;
        int consecutiveApiErrors = 0;
        boolean aborted = false;

        for (Housing housing : targets) {
            // 지연 로딩을 건드리지 않도록 기본 필드만 읽는다. (트랜잭션 밖이다)
            InfraCollectionResult result = collector.collect(
                    housing.getId(), housing.getLatitude(), housing.getLongitude()
            );
            apiCalls += result.apiCalls();

            switch (result.status()) {
                case SUCCESS -> {
                    persistenceService.save(
                            housing.getId(),
                            housing.getLatitude(),
                            housing.getLongitude(),
                            result.categories(),
                            Instant.now()
                    );
                    saved++;
                    consecutiveApiErrors = 0;
                }
                // 이 주택만의 데이터 문제다. 기존 캐시는 그대로 두고 다음 주택으로 넘어간다.
                case DATA_ERROR -> {
                    dataErrors++;
                    consecutiveApiErrors = 0;
                }
                // 카카오 쪽 문제일 가능성이 높다. 연속으로 이어지면 회차를 중단해 헛호출을 막는다.
                case API_ERROR -> {
                    apiErrors++;
                    consecutiveApiErrors++;
                }
            }

            if (consecutiveApiErrors >= properties.collection().apiFailureThreshold()) {
                log.warn("카카오 호출이 연속 {}회 실패해 이번 주변 인프라 수집 회차를 중단합니다.", consecutiveApiErrors);
                aborted = true;
                break;
            }
        }

        CollectionSummary summary = new CollectionSummary(
                targets.size(), saved, dataErrors, apiErrors, apiCalls, aborted
        );
        log.info("주변 인프라 수집 완료: 대상={}, 저장={}, 데이터오류={}, 호출실패={}, 카카오 호출={}, 중단={}",
                summary.targets(), summary.saved(), summary.dataErrors(),
                summary.apiErrors(), summary.apiCalls(), summary.aborted());
        return summary;
    }

    public record CollectionSummary(
            int targets,
            int saved,
            int dataErrors,
            int apiErrors,
            int apiCalls,
            boolean aborted
    ) {
    }
}
