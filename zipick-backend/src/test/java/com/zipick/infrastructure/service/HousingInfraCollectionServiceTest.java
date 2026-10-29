package com.zipick.infrastructure.service;

import com.zipick.external.kakao.config.KakaoLocalApiProperties;
import com.zipick.infrastructure.config.InfraPolicy;
import com.zipick.infrastructure.config.InfraProperties;
import com.zipick.infrastructure.config.InfraProperties.Category;
import com.zipick.infrastructure.config.InfraProperties.Collection;
import com.zipick.infrastructure.repository.HousingInfraSnapshotRepository;
import com.zipick.infrastructure.service.InfraCollectionResult.CollectedCategory;
import com.zipick.infrastructure.service.InfraCollectionResult.NearestPlace;
import com.zipick.notice.domain.Housing;
import com.zipick.housing.dto.HouseFilter;
import com.zipick.notice.domain.Notice;
import com.zipick.notice.domain.NoticeStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class HousingInfraCollectionServiceTest {

    private static final Category SUBWAY = new Category("SUBWAY", "SW8", "지하철역", 1500);
    private static final BigDecimal LATITUDE = new BigDecimal("37.5756000");
    private static final BigDecimal LONGITUDE = new BigDecimal("127.0250000");

    @Mock
    private HousingInfraSnapshotRepository snapshotRepository;
    @Mock
    private HousingInfraCollector collector;
    @Mock
    private HousingInfraPersistenceService persistenceService;

    /** 저장에 성공한 주택. 실제 쿼리와 같게, 저장된 주택은 다음 batch 대상에서 빠진다. */
    private final Set<Long> persisted = new HashSet<>();
    /** 수집을 시도한 순서. 같은 주택이 한 실행 안에서 다시 선택되는지 확인한다. */
    private final List<Long> attempted = new ArrayList<>();

    @Test
    @DisplayName("수집에 성공한 주택만 저장한다")
    void savesSuccessfulHousings() {
        givenTargets(housings(1L, 2L));
        givenCollect(1L, success());
        givenCollect(2L, success());
        HousingInfraCollectionService service = service(configuredKey());

        HousingInfraCollectionService.CollectionSummary summary = service.drain();

        assertThat(summary.targets()).isEqualTo(2);
        assertThat(summary.saved()).isEqualTo(2);
        verify(persistenceService, times(2)).save(anyLong(), any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("한 batch 보다 대상이 많으면 대기 없이 다음 batch 를 이어서 모두 처리한다")
    void drainsEveryBatchInOneRun() {
        givenTargets(housings(1L, 2L, 3L, 4L, 5L));
        givenCollectAll(success());
        // batch 2개씩 → 2 + 2 + 1 로 세 batch 를 연속 처리하고, 네 번째 조회에서 비어 끝난다
        HousingInfraCollectionService service = service(configuredKey(), collection(true, 2, 3));

        HousingInfraCollectionService.CollectionSummary summary = service.drain();

        assertThat(summary.batches()).isEqualTo(3);
        assertThat(summary.targets()).isEqualTo(5);
        assertThat(summary.saved()).isEqualTo(5);
        assertThat(attempted).containsExactly(1L, 2L, 3L, 4L, 5L);
        verify(snapshotRepository, times(4)).findCollectionTargets(
                any(), any(), any(), anyLong(), any(Pageable.class));
    }

    @Test
    @DisplayName("저장에 성공한 주택은 다음 batch 에서 다시 선택되지 않는다")
    void doesNotReselectPersistedHousings() {
        givenTargets(housings(1L, 2L, 3L, 4L));
        givenCollectAll(success());
        HousingInfraCollectionService service = service(configuredKey(), collection(true, 2, 3));

        service.drain();

        // 주택마다 정확히 한 번씩만 수집한다
        assertThat(attempted).containsExactly(1L, 2L, 3L, 4L);
        assertThat(persisted).containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
    }

    @Test
    @Timeout(10)
    @DisplayName("저장되지 않은 실패 주택이 같은 실행 안에서 다시 선택되지 않아 무한 루프가 생기지 않는다")
    void failedHousingsDoNotLoopForever() {
        // 전부 데이터 오류라 저장되지 않는다. 커서가 없다면 같은 batch 가 영원히 다시 조회된다.
        givenTargets(housings(1L, 2L, 3L));
        givenCollectAll(InfraCollectionResult.dataError(6));
        HousingInfraCollectionService service = service(configuredKey(), collection(true, 2, 3));

        HousingInfraCollectionService.CollectionSummary summary = service.drain();

        assertThat(summary.dataErrors()).isEqualTo(3);
        assertThat(summary.saved()).isZero();
        assertThat(attempted).containsExactly(1L, 2L, 3L);
        verifyNoInteractions(persistenceService);
    }

    @Test
    @DisplayName("한 주택의 데이터 오류 때문에 다른 주택 수집이 멈추지 않는다")
    void continuesAfterDataError() {
        givenTargets(housings(1L, 2L));
        givenCollect(1L, InfraCollectionResult.dataError(6));
        givenCollect(2L, success());
        HousingInfraCollectionService service = service(configuredKey());

        HousingInfraCollectionService.CollectionSummary summary = service.drain();

        assertThat(summary.dataErrors()).isEqualTo(1);
        assertThat(summary.saved()).isEqualTo(1);
        assertThat(summary.aborted()).isFalse();
        // 실패한 주택은 저장하지 않으므로 기존 캐시가 그대로 남는다
        verify(persistenceService, times(1)).save(eq(2L), any(), any(), anyList(), any());
        verify(persistenceService, never()).save(eq(1L), any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("카카오 호출이 연속 실패하면 batch 가 남아 있어도 실행 전체를 중단한다")
    void abortsAfterConsecutiveApiErrors() {
        givenTargets(housings(1L, 2L, 3L, 4L, 5L, 6L));
        givenCollectAll(InfraCollectionResult.apiError(1));
        HousingInfraCollectionService service = service(configuredKey(), collection(true, 2, 2));

        HousingInfraCollectionService.CollectionSummary summary = service.drain();

        assertThat(summary.aborted()).isTrue();
        assertThat(summary.apiErrors()).isEqualTo(2);
        // 첫 batch 안에서 임계치에 닿았으므로 두 번째 batch 를 조회조차 하지 않는다
        assertThat(summary.batches()).isEqualTo(1);
        verify(collector, times(2)).collect(anyLong(), any(), any());
        verifyNoInteractions(persistenceService);
    }

    @Test
    @DisplayName("수집기 프로세스가 부르는 collect 는 실행 결과 요약을 돌려준다")
    void collectReturnsSummaryForCollector() {
        givenTargets(housings(1L, 2L));
        givenCollectAll(success());

        Optional<HousingInfraCollectionService.CollectionSummary> summary =
                service(configuredKey()).collect("일일 수집");

        assertThat(summary).isPresent();
        assertThat(summary.get().saved()).isEqualTo(2);
    }

    @Test
    @DisplayName("수집이 꺼져 있으면 collect 는 빈 값을 돌려주고 아무것도 조회하지 않는다")
    void collectReturnsEmptyWhenDisabled() {
        Optional<HousingInfraCollectionService.CollectionSummary> summary =
                service(configuredKey(), collection(false, 100, 3)).collect("일일 수집");

        assertThat(summary).isEmpty();
        verifyNoInteractions(snapshotRepository, collector, persistenceService);
    }

    @Test
    @DisplayName("수집 중 예상치 못한 오류가 나도 collect 는 예외를 던지지 않는다")
    void collectSwallowsUnexpectedErrors() {
        given(snapshotRepository.findCollectionTargets(any(), any(), any(), anyLong(), any(Pageable.class)))
                .willThrow(new IllegalStateException("db down"));

        Optional<HousingInfraCollectionService.CollectionSummary> summary =
                service(configuredKey()).collect("일일 수집");

        assertThat(summary).isEmpty();
        verifyNoInteractions(collector, persistenceService);
    }

    @Test
    @DisplayName("API 키가 없으면 대상 조회조차 하지 않는다")
    void skipsWhenApiKeyMissing() {
        Optional<HousingInfraCollectionService.CollectionSummary> summary =
                service(new KakaoLocalApiProperties(null, null)).collect("일일 수집");

        assertThat(summary).isEmpty();
        verifyNoInteractions(snapshotRepository, collector, persistenceService);
    }

    @Test
    @DisplayName("수집 대상이 없으면 카카오를 호출하지 않는다")
    void doesNotCallKakaoWithoutTargets() {
        givenTargets(List.of());

        Optional<HousingInfraCollectionService.CollectionSummary> summary =
                service(configuredKey()).collect("일일 수집");

        assertThat(summary).isPresent();
        assertThat(summary.get().targets()).isZero();
        verifyNoInteractions(collector, persistenceService);
    }

    /**
     * 실제 쿼리와 같은 의미로 답한다.
     *   - 커서보다 큰 id 만
     *   - 저장에 성공한 주택은 조건에서 빠진다 (실패한 주택은 그대로 남는다)
     *   - batch size 만큼만
     * 커서가 없으면 실패 주택 때문에 같은 batch 가 무한히 반복된다는 점을 이 stub 이 그대로 드러낸다.
     */
    private void givenTargets(List<Housing> all) {
        given(snapshotRepository.findCollectionTargets(
                eq(HouseFilter.OPEN_STATUSES), any(Instant.class), any(String.class),
                anyLong(), any(Pageable.class)
        )).willAnswer(invocation -> {
            long cursor = invocation.getArgument(3);
            Pageable pageable = invocation.getArgument(4);
            return all.stream()
                    .filter(housing -> housing.getId() > cursor)
                    .filter(housing -> !persisted.contains(housing.getId()))
                    .limit(pageable.getPageSize())
                    .toList();
        });
        lenient().doAnswer(invocation -> {
            persisted.add(invocation.getArgument(0));
            return null;
        }).when(persistenceService).save(anyLong(), any(), any(), anyList(), any());
    }

    private void givenCollect(Long housingId, InfraCollectionResult result) {
        given(collector.collect(eq(housingId), any(), any())).willAnswer(invocation -> {
            attempted.add(invocation.getArgument(0));
            return result;
        });
    }

    private void givenCollectAll(InfraCollectionResult result) {
        given(collector.collect(anyLong(), any(), any())).willAnswer(invocation -> {
            attempted.add(invocation.getArgument(0));
            return result;
        });
    }



    private HousingInfraCollectionService service(KakaoLocalApiProperties kakaoProperties) {
        return service(kakaoProperties, collection(true, 100, 3));
    }

    private HousingInfraCollectionService service(
            KakaoLocalApiProperties kakaoProperties, Collection collection
    ) {
        InfraProperties properties = properties(collection);
        return new HousingInfraCollectionService(
                snapshotRepository,
                collector,
                persistenceService,
                properties,
                new InfraPolicy(properties),
                kakaoProperties
        );
    }

    private static InfraProperties properties(Collection collection) {
        return new InfraProperties(Duration.ofDays(30), collection, List.of(SUBWAY));
    }

    private static Collection collection(boolean enabled, int batchSize, int apiFailureThreshold) {
        return new Collection(enabled, batchSize, apiFailureThreshold);
    }

    private static KakaoLocalApiProperties configuredKey() {
        return new KakaoLocalApiProperties("test-key", null);
    }

    private static InfraCollectionResult success() {
        return InfraCollectionResult.success(
                List.of(new CollectedCategory(SUBWAY, 6,
                        new NearestPlace("1", "신설동역", 683, null, null, null, null, null))),
                1
        );
    }

    private static List<Housing> housings(Long... ids) {
        Set<Long> unique = new LinkedHashSet<>(List.of(ids));
        return unique.stream().map(HousingInfraCollectionServiceTest::housing).toList();
    }

    private static Housing housing(Long id) {
        Notice notice = new Notice("MYHOME", "notice-" + id, "공고", NoticeStatus.OPEN);
        Housing housing = new Housing(notice, "housing-" + id, "주택", "서울특별시 동대문구 왕산로 1");
        housing.updateCoordinates(LATITUDE, LONGITUDE);
        ReflectionTestUtils.setField(housing, "id", id);
        return housing;
    }
}
