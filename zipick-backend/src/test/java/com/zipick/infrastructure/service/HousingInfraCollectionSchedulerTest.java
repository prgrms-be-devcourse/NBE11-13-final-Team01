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
import com.zipick.notice.domain.Notice;
import com.zipick.notice.domain.NoticeStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class HousingInfraCollectionSchedulerTest {

    private static final Category SUBWAY = new Category("SUBWAY", "SW8", "지하철역", 1500);
    private static final BigDecimal LATITUDE = new BigDecimal("37.5756000");
    private static final BigDecimal LONGITUDE = new BigDecimal("127.0250000");

    @Mock
    private HousingInfraSnapshotRepository snapshotRepository;
    @Mock
    private HousingInfraCollector collector;
    @Mock
    private HousingInfraPersistenceService persistenceService;

    @Test
    @DisplayName("수집 대상이 없으면 카카오를 호출하지 않는다")
    void doesNotCallKakaoWithoutTargets() {
        givenTargets(List.of());
        HousingInfraCollectionScheduler scheduler = scheduler(configuredKey());

        scheduler.collectPendingHousings();

        verifyNoInteractions(collector, persistenceService);
    }

    @Test
    @DisplayName("API 키가 없으면 대상 조회조차 하지 않는다")
    void skipsWhenApiKeyMissing() {
        HousingInfraCollectionScheduler scheduler = scheduler(
                new KakaoLocalApiProperties(null, null)
        );

        scheduler.collectPendingHousings();

        verifyNoInteractions(snapshotRepository, collector, persistenceService);
    }

    @Test
    @DisplayName("설정으로 수집을 끄면 아무것도 하지 않는다")
    void skipsWhenDisabled() {
        HousingInfraCollectionScheduler scheduler = scheduler(
                configuredKey(), new Collection(false, 100, 3)
        );

        scheduler.collectPendingHousings();

        verifyNoInteractions(snapshotRepository, collector, persistenceService);
    }

    @Test
    @DisplayName("수집에 성공한 주택만 저장한다")
    void savesSuccessfulHousings() {
        givenTargets(List.of(housing(1L), housing(2L)));
        given(collector.collect(eq(1L), any(), any())).willReturn(success());
        given(collector.collect(eq(2L), any(), any())).willReturn(success());
        HousingInfraCollectionScheduler scheduler = scheduler(configuredKey());

        HousingInfraCollectionScheduler.CollectionSummary summary = scheduler.runOnce();

        assertThat(summary.targets()).isEqualTo(2);
        assertThat(summary.saved()).isEqualTo(2);
        verify(persistenceService, times(2)).save(anyLong(), any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("한 주택의 데이터 오류 때문에 다른 주택 수집이 멈추지 않는다")
    void continuesAfterDataError() {
        givenTargets(List.of(housing(1L), housing(2L)));
        given(collector.collect(eq(1L), any(), any())).willReturn(InfraCollectionResult.dataError(6));
        given(collector.collect(eq(2L), any(), any())).willReturn(success());
        HousingInfraCollectionScheduler scheduler = scheduler(configuredKey());

        HousingInfraCollectionScheduler.CollectionSummary summary = scheduler.runOnce();

        assertThat(summary.dataErrors()).isEqualTo(1);
        assertThat(summary.saved()).isEqualTo(1);
        assertThat(summary.aborted()).isFalse();
        // 실패한 주택은 저장하지 않으므로 기존 캐시가 그대로 남는다
        verify(persistenceService, times(1)).save(eq(2L), any(), any(), anyList(), any());
        verify(persistenceService, never()).save(eq(1L), any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("카카오 호출이 연속 실패하면 회차를 중단해 헛호출을 막는다")
    void abortsAfterConsecutiveApiErrors() {
        givenTargets(List.of(housing(1L), housing(2L), housing(3L), housing(4L)));
        given(collector.collect(anyLong(), any(), any())).willReturn(InfraCollectionResult.apiError(1));
        HousingInfraCollectionScheduler scheduler = scheduler(
                configuredKey(), new Collection(true, 100, 2)
        );

        HousingInfraCollectionScheduler.CollectionSummary summary = scheduler.runOnce();

        assertThat(summary.aborted()).isTrue();
        assertThat(summary.apiErrors()).isEqualTo(2);
        verify(collector, times(2)).collect(anyLong(), any(), any());
        verifyNoInteractions(persistenceService);
    }

    private void givenTargets(List<Housing> targets) {
        given(snapshotRepository.findCollectionTargets(
                eq(NoticeStatus.OPEN), any(Instant.class), any(String.class), any(Pageable.class)
        )).willReturn(targets);
    }

    private HousingInfraCollectionScheduler scheduler(KakaoLocalApiProperties kakaoProperties) {
        return scheduler(kakaoProperties, new Collection(true, 100, 3));
    }

    private HousingInfraCollectionScheduler scheduler(
            KakaoLocalApiProperties kakaoProperties, Collection collection
    ) {
        InfraProperties properties = new InfraProperties(
                Duration.ofDays(30), collection, List.of(SUBWAY)
        );
        return new HousingInfraCollectionScheduler(
                snapshotRepository,
                collector,
                persistenceService,
                properties,
                new InfraPolicy(properties),
                kakaoProperties
        );
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

    private static Housing housing(Long id) {
        Notice notice = new Notice("MYHOME", "notice-" + id, "공고", NoticeStatus.OPEN);
        Housing housing = new Housing(notice, "housing-" + id, "주택", "서울특별시 동대문구 왕산로 1");
        housing.updateCoordinates(LATITUDE, LONGITUDE);
        ReflectionTestUtils.setField(housing, "id", id);
        return housing;
    }
}
