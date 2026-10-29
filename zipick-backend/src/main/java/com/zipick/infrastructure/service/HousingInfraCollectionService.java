package com.zipick.infrastructure.service;

import com.zipick.external.kakao.config.KakaoLocalApiProperties;
import com.zipick.infrastructure.config.InfraPolicy;
import com.zipick.infrastructure.config.InfraProperties;
import com.zipick.notice.domain.Housing;
import com.zipick.housing.dto.HouseFilter;
import com.zipick.infrastructure.repository.HousingInfraSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 주변 인프라를 미리 채워 두는 수집 로직.
 *
 * 사용자 조회 경로에서는 외부 API 를 호출하지 않는다. 지도 상세와 추천 모두
 * 여기서 저장해 둔 DB 데이터를 읽는다. 그래서 조회 응답이 카카오 지연에 묶이지 않는다.
 *
 * <h3>누가 부르나</h3>
 * collector 프로세스의 {@code InfraCollectionApplication} 하나뿐이다. 매일 수집(run-daily-collection.sh)이
 * 기본 수집 → AI 수집을 마친 뒤 마지막에 한 번 부른다. 두 수집이 만든 주택의 좌표가 모두 채워진 뒤라 한 번으로 충분하고,
 * 앞 단계가 실패해도 이 단계는 실행된다. 웹 서버는 인프라를 수집하지 않는다.
 *
 * 수집 대상은 스냅샷 없음 / TTL 만료 / 정책 변경 / 좌표 변경 네 가지이며 쿼리 하나로 찾는다.
 * 실패한 주택은 저장되지 않으므로 다음 실행에 자동으로 다시 대상이 된다. 별도 재시도 장치가 없다.
 */
@Service
public class HousingInfraCollectionService {

    private static final Logger log = LoggerFactory.getLogger(HousingInfraCollectionService.class);

    /** 커서 페이징의 시작값. 주택 id 는 1 부터라 0 보다 크다는 조건이 곧 전체 조회가 된다. */
    private static final long FIRST_CURSOR = 0L;

    /** 같은 프로세스 안의 중복 실행 방지. (프로세스 간 중복은 수집 스크립트의 flock 이 막는다) */
    private final ReentrantLock lock = new ReentrantLock();

    private final HousingInfraSnapshotRepository snapshotRepository;
    private final HousingInfraCollector collector;
    private final HousingInfraPersistenceService persistenceService;
    private final InfraProperties properties;
    private final InfraPolicy policy;
    private final KakaoLocalApiProperties kakaoProperties;

    private boolean missingKeyLogged;

    public HousingInfraCollectionService(
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
     * 수집 한 번. 꺼져 있거나 키가 없거나 다른 수집이 진행 중이면 건너뛰고 빈 값을 돌려준다.
     * 예외는 여기서 막는다. 호출하는 쪽(스케줄러 스레드·수집기 프로세스)이 이 실패로 멈추지 않게 하기 위해서다.
     */
    public Optional<CollectionSummary> collect(String trigger) {
        if (!properties.collection().enabled()) {
            return Optional.empty();
        }
        if (!kakaoProperties.isConfigured()) {
            if (!missingKeyLogged) {
                log.warn("카카오 REST API 키가 없어 주변 인프라 수집을 건너뜁니다.");
                missingKeyLogged = true;
            }
            return Optional.empty();
        }
        if (!lock.tryLock()) {
            log.info("이전 주변 인프라 수집이 아직 진행 중이라 {} 수집을 건너뜁니다.", trigger);
            return Optional.empty();
        }
        try {
            return Optional.of(drain());
        } catch (RuntimeException exception) {
            // 남은 대상은 다음 실행에 다시 처리된다.
            log.error("주변 인프라 수집 중 예상치 못한 오류가 발생했습니다.", exception);
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 대상이 없어질 때까지 batch 단위로 이어서 처리한다.
     *
     * <p>batch 사이에 대기를 두지 않는다. 대기를 없애도 <b>실행 중의 초당 호출 속도는 그대로다</b>.
     * 주택도 카테고리도 순차 호출이라 동시 호출이 생기지 않기 때문이다. 유휴 시간만 사라진다.
     *
     * <p>커서(마지막으로 처리한 주택 id)를 들고 다음 batch 를 조회한다. 그래서
     * <b>한 번의 실행 안에서는 이미 시도한 주택이 다시 선택되지 않는다.</b> 저장에 실패한 주택은
     * 조회 조건을 계속 만족하므로, 커서 없이 같은 조건을 다시 읽으면 같은 batch 가 무한히 돌아온다.
     * 커서는 성공·실패와 무관하게 전진하고 조회는 커서보다 큰 id 만 보므로 종료가 보장된다.
     * 실패한 주택은 다음 실행에서 다시 대상이 된다(기존 재시도 동작 그대로).
     *
     * <p>TTL 기준 시각과 정책 서명은 실행 시작 시점에 한 번만 구한다.
     * 실행이 길어져도 중간에 대상 집합이 흔들리지 않게 하기 위해서다.
     *
     * <p>batch 하나를 끝낼 때마다 진행 상황을 남긴다. 전건 수집은 수십 분 걸리는데 완료 로그 하나만
     * 남기면 그동안 진행 중인지 멈춘 것인지 구분할 수 없기 때문이다.
     */
    CollectionSummary drain() {
        Instant startedAt = Instant.now();
        Instant ttlThreshold = startedAt.minus(properties.cacheTtl());
        String signature = policy.signature();
        Pageable batch = PageRequest.of(0, properties.collection().batchSize());

        long cursor = FIRST_CURSOR;
        int batches = 0;
        int targets = 0;
        int saved = 0;
        int dataErrors = 0;
        int apiErrors = 0;
        int apiCalls = 0;
        int consecutiveApiErrors = 0;
        boolean aborted = false;

        while (!aborted) {
            // 추천 후보와 같은 상태 집합을 쓴다. 추천 쪽에서 바뀌면 수집 범위도 자동으로 따라간다.
            List<Housing> housings = snapshotRepository.findCollectionTargets(
                    HouseFilter.OPEN_STATUSES, ttlThreshold, signature, cursor, batch
            );
            if (housings.isEmpty()) {
                break;
            }
            batches++;
            int batchTargets = housings.size();
            int batchSaved = 0;
            targets += batchTargets;

            for (Housing housing : housings) {
                // 처리 결과와 무관하게 커서를 넘긴다. 실패한 주택을 이번 실행에서 다시 집지 않기 위해서다.
                cursor = housing.getId();

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
                        batchSaved++;
                        consecutiveApiErrors = 0;
                    }
                    // 이 주택만의 데이터 문제다. 기존 캐시는 그대로 두고 다음 주택으로 넘어간다.
                    case DATA_ERROR -> {
                        dataErrors++;
                        consecutiveApiErrors = 0;
                    }
                    // 카카오 쪽 문제일 가능성이 높다. 연속으로 이어지면 실행을 중단해 헛호출을 막는다.
                    case API_ERROR -> {
                        apiErrors++;
                        consecutiveApiErrors++;
                    }
                }

                if (consecutiveApiErrors >= properties.collection().apiFailureThreshold()) {
                    log.warn("카카오 호출이 연속 {}회 실패해 이번 주변 인프라 수집을 중단합니다.", consecutiveApiErrors);
                    aborted = true;
                    break;
                }
            }

            // 중단된 batch 도 여기까지 온다. 어디까지 처리하고 멈췄는지 남기기 위해서다.
            log.info("주변 인프라 배치 완료: batch={}, 대상={}, 저장={}, 누적대상={}, 누적저장={}",
                    batches, batchTargets, batchSaved, targets, saved);
        }

        CollectionSummary summary = new CollectionSummary(
                batches, targets, saved, dataErrors, apiErrors, apiCalls, aborted
        );
        if (targets > 0 || aborted) {
            // startup delay 나 batch 사이 대기는 들어 있지 않다. 실제 수집에 걸린 시간만 센다.
            long elapsedSeconds = Duration.between(startedAt, Instant.now()).toSeconds();
            log.info("주변 인프라 수집 완료: batch={}, 대상={}, 저장={}, 데이터오류={}, 호출실패={}, 카카오 호출={}, 중단={}, elapsedSeconds={}",
                    summary.batches(), summary.targets(), summary.saved(), summary.dataErrors(),
                    summary.apiErrors(), summary.apiCalls(), summary.aborted(), elapsedSeconds);
        }
        return summary;
    }

    public record CollectionSummary(
            int batches,
            int targets,
            int saved,
            int dataErrors,
            int apiErrors,
            int apiCalls,
            boolean aborted
    ) {
    }
}
