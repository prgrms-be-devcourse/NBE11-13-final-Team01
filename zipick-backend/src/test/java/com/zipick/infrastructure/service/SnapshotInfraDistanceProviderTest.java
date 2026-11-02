package com.zipick.infrastructure.service;

import com.zipick.housing.domain.Candidate;
import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import com.zipick.infrastructure.config.InfraPolicy;
import com.zipick.infrastructure.config.InfraProperties;
import com.zipick.infrastructure.config.InfraProperties.Category;
import com.zipick.infrastructure.domain.HousingInfraSnapshot;
import com.zipick.infrastructure.repository.HousingInfraSnapshotRepository;
import com.zipick.notice.domain.NoticeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SnapshotInfraDistanceProviderTest {

    private static final Category SUBWAY = new Category("SUBWAY", "SW8", "지하철역", 1500);
    private static final Category MART = new Category("MART", "MT1", "대형마트", 1500);
    private static final BigDecimal LATITUDE = new BigDecimal("37.5756000");
    private static final BigDecimal LONGITUDE = new BigDecimal("127.0250000");

    @Mock
    private HousingInfraSnapshotRepository snapshotRepository;

    private InfraPolicy policy;
    private SnapshotInfraDistanceProvider provider;

    @BeforeEach
    void setUp() {
        policy = new InfraPolicy(new InfraProperties(null, null, List.of(SUBWAY, MART)));
        provider = new SnapshotInfraDistanceProvider(snapshotRepository, policy);
    }

    @Test
    @DisplayName("nearest JSON 을 추천이 쓰는 거리 Map 으로 바꾼다")
    void convertsNearestJsonToDistances() {
        givenSnapshots(snapshot(1L, policy.signature(), LATITUDE, LONGITUDE, Instant.now(),
                "{\"SW8\":{\"name\":\"신설동역\",\"distanceM\":683},\"MT1\":{\"name\":\"마트\",\"distanceM\":750}}"));

        Map<Long, Map<String, Integer>> result = provider.nearest(List.of(candidate(10L, 1L)));

        assertThat(result).containsOnlyKeys(1L);
        assertThat(result.get(1L)).containsExactlyInAnyOrderEntriesOf(Map.of("SW8", 683, "MT1", 750));
    }

    @Test
    @DisplayName("TTL 이 지난 스냅샷도 정책·좌표가 같으면 그대로 쓴다")
    void usesExpiredSnapshotWhenContextUnchanged() {
        givenSnapshots(snapshot(1L, policy.signature(), LATITUDE, LONGITUDE,
                Instant.now().minus(Duration.ofDays(90)),
                "{\"SW8\":{\"name\":\"신설동역\",\"distanceM\":683}}"));

        Map<Long, Map<String, Integer>> result = provider.nearest(List.of(candidate(10L, 1L)));

        assertThat(result.get(1L)).containsEntry("SW8", 683);
    }

    @Test
    @DisplayName("수집 정책이 바뀐 스냅샷은 거리가 더 이상 맞지 않으므로 제외한다")
    void excludesSnapshotCollectedUnderDifferentPolicy() {
        givenSnapshots(snapshot(1L, "SUBWAY:SW8:1000", LATITUDE, LONGITUDE, Instant.now(),
                "{\"SW8\":{\"name\":\"신설동역\",\"distanceM\":683}}"));

        assertThat(provider.nearest(List.of(candidate(10L, 1L)))).isEmpty();
    }

    @Test
    @DisplayName("주택 좌표가 바뀐 스냅샷은 다른 위치를 잰 값이라 제외한다")
    void excludesSnapshotCollectedAtDifferentCoordinate() {
        givenSnapshots(snapshot(1L, policy.signature(), new BigDecimal("37.6000000"), LONGITUDE,
                Instant.now(), "{\"SW8\":{\"name\":\"신설동역\",\"distanceM\":683}}"));

        assertThat(provider.nearest(List.of(candidate(10L, 1L)))).isEmpty();
    }

    @Test
    @DisplayName("주변이 전부 0건이어도 카테고리 key 는 모두 담는다 (값은 null) — 추천이 0점으로 센다")
    void keepsAllCategoryKeysForZeroResult() {
        givenSnapshots(snapshot(1L, policy.signature(), LATITUDE, LONGITUDE, Instant.now(), "{}"));

        Map<Long, Map<String, Integer>> result = provider.nearest(List.of(candidate(10L, 1L)));

        assertThat(result).containsOnlyKeys(1L);
        assertThat(result.get(1L)).containsOnlyKeys("SW8", "MT1");
        assertThat(result.get(1L).get("SW8")).isNull();
        assertThat(result.get(1L).get("MT1")).isNull();
    }

    @Test
    @DisplayName("일부 카테고리만 검색되면 나머지는 null 로 담아 0점 대상이 되게 한다")
    void keepsNullForCategoriesWithoutPlace() {
        givenSnapshots(snapshot(1L, policy.signature(), LATITUDE, LONGITUDE, Instant.now(),
                "{\"SW8\":{\"name\":\"신설동역\",\"distanceM\":683}}"));

        Map<String, Integer> distances = provider.nearest(List.of(candidate(10L, 1L))).get(1L);

        assertThat(distances).containsOnlyKeys("SW8", "MT1");
        assertThat(distances).containsEntry("SW8", 683);
        assertThat(distances.get("MT1")).isNull();
    }

    @Test
    @DisplayName("아직 수집되지 않은 주택은 key 자체가 없다")
    void omitsHousingWithoutSnapshot() {
        givenSnapshots();

        assertThat(provider.nearest(List.of(candidate(10L, 1L)))).doesNotContainKey(1L);
    }

    @Test
    @DisplayName("같은 주택의 호실이 여러 개여도 주택 단위로 한 번만 조회한다")
    void deduplicatesHousingIds() {
        givenSnapshots(snapshot(1L, policy.signature(), LATITUDE, LONGITUDE, Instant.now(),
                "{\"SW8\":{\"name\":\"신설동역\",\"distanceM\":683}}"));

        Map<Long, Map<String, Integer>> result = provider.nearest(
                List.of(candidate(10L, 1L), candidate(11L, 1L), candidate(12L, 1L))
        );

        assertThat(result).hasSize(1);
        // 조회는 주택 id 3개가 아니라 1개로 들어간다
        verify(snapshotRepository).findAllByHousingIdIn(argThat(ids -> ids.size() == 1));
    }

    @Test
    @DisplayName("후보가 없으면 DB 를 조회하지 않는다")
    void doesNotQueryWithoutCandidates() {
        assertThat(provider.nearest(List.of())).isEmpty();
        assertThat(provider.nearest(null)).isEmpty();

        verifyNoInteractions(snapshotRepository);
    }

    @Test
    @DisplayName("distanceM 이 없거나 형식이 깨진 항목은 '없음'(null)으로 본다")
    void treatsBrokenEntriesAsMissing() {
        List<Category> categories = List.of(SUBWAY, MART);

        Map<String, Integer> broken = SnapshotInfraDistanceProvider.toDistances(
                "{\"SW8\":{\"name\":\"역\"},\"MT1\":{\"name\":\"마트\",\"distanceM\":750}}", categories);
        assertThat(broken.get("SW8")).isNull();
        assertThat(broken).containsEntry("MT1", 750);

        // JSON 자체가 비정상이어도 카테고리 key 는 유지한다 (정책 대상임은 분명하므로)
        assertThat(SnapshotInfraDistanceProvider.toDistances(null, categories))
                .containsOnlyKeys("SW8", "MT1");
        assertThat(SnapshotInfraDistanceProvider.toDistances("not json", categories).get("SW8")).isNull();
    }

    private void givenSnapshots(HousingInfraSnapshot... snapshots) {
        given(snapshotRepository.findAllByHousingIdIn(anyCollection())).willReturn(List.of(snapshots));
    }

    private static HousingInfraSnapshot snapshot(
            Long housingId, String signature, BigDecimal latitude, BigDecimal longitude,
            Instant collectedAt, String nearest
    ) {
        return new HousingInfraSnapshot(housingId, signature, latitude, longitude, nearest, collectedAt);
    }

    private static Candidate candidate(Long supplyId, Long housingId) {
        return new Candidate(
                supplyId, housingId, 100L, "공고", "매입임대", RentalType.PURCHASE, NoticeStatus.OPEN,
                null, null, "LH", "LH", "주택", "서울특별시 동대문구 왕산로 1", "서울특별시", "동대문구",
                LATITUDE, LONGITUDE, 2015, "아파트", HousingType.APARTMENT,
                new BigDecimal("30.00"), 2, 3, 1000, 20, 1
        );
    }
}
