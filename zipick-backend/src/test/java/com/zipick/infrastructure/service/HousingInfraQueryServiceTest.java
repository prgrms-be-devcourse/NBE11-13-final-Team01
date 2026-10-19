package com.zipick.infrastructure.service;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.infrastructure.config.InfraPolicy;
import com.zipick.infrastructure.config.InfraProperties;
import com.zipick.infrastructure.config.InfraProperties.Category;
import com.zipick.infrastructure.domain.HousingInfraPlace;
import com.zipick.infrastructure.domain.HousingInfraSnapshot;
import com.zipick.infrastructure.dto.HousingInfraResponse;
import com.zipick.infrastructure.dto.InfraStatus;
import com.zipick.infrastructure.repository.HousingInfraPlaceRepository;
import com.zipick.infrastructure.repository.HousingInfraSnapshotRepository;
import com.zipick.notice.domain.Housing;
import com.zipick.notice.domain.Notice;
import com.zipick.notice.domain.NoticeStatus;
import com.zipick.notice.repository.HousingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class HousingInfraQueryServiceTest {

    private static final Long HOUSING_ID = 1L;
    private static final Category SUBWAY = new Category("SUBWAY", "SW8", "지하철역", 1500);
    private static final Category MART = new Category("MART", "MT1", "대형마트", 1000);
    private static final BigDecimal LATITUDE = new BigDecimal("37.5756000");
    private static final BigDecimal LONGITUDE = new BigDecimal("127.0250000");

    @Mock
    private HousingRepository housingRepository;
    @Mock
    private HousingInfraSnapshotRepository snapshotRepository;
    @Mock
    private HousingInfraPlaceRepository placeRepository;

    private InfraPolicy policy;
    private HousingInfraQueryService service;

    @BeforeEach
    void setUp() {
        InfraProperties properties = new InfraProperties(
                Duration.ofDays(30), null, List.of(SUBWAY, MART)
        );
        policy = new InfraPolicy(properties);
        service = new HousingInfraQueryService(
                housingRepository, snapshotRepository, placeRepository, properties, policy
        );
    }

    @Test
    @DisplayName("유효한 스냅샷이 있으면 FRESH 로 설정 순서대로 내려준다")
    void returnsFreshInConfiguredOrder() {
        givenHousing(housing(LATITUDE, LONGITUDE));
        HousingInfraSnapshot snapshot = givenSnapshot(Instant.now(), policy.signature(), LATITUDE, LONGITUDE);
        // 저장 순서가 뒤섞여 있어도 설정 순서로 정렬된다
        given(placeRepository.findAllBySnapshotIdOrderByIdAsc(snapshot.getId()))
                .willReturn(List.of(place("MART", "마트", 750, 2), place("SUBWAY", "신설동역", 683, 6)));

        HousingInfraResponse response = service.find(HOUSING_ID);

        assertThat(response.status()).isEqualTo(InfraStatus.FRESH);
        assertThat(response.distanceBasis()).isEqualTo("STRAIGHT_LINE");
        assertThat(response.categories()).extracting("code").containsExactly("SUBWAY", "MART");
        assertThat(response.categories().getFirst().label()).isEqualTo("지하철역");
        assertThat(response.categories().getFirst().nearestName()).isEqualTo("신설동역");
        assertThat(response.categories().getFirst().nearestDistanceMeter()).isEqualTo(683);
        assertThat(response.categories().getFirst().totalCount()).isEqualTo(6);
    }

    @Test
    @DisplayName("정상 수집했지만 주변에 아무것도 없으면 FRESH + 빈 목록이다")
    void returnsFreshWithEmptyCategories() {
        givenHousing(housing(LATITUDE, LONGITUDE));
        HousingInfraSnapshot snapshot = givenSnapshot(Instant.now(), policy.signature(), LATITUDE, LONGITUDE);
        given(placeRepository.findAllBySnapshotIdOrderByIdAsc(snapshot.getId())).willReturn(List.of());

        HousingInfraResponse response = service.find(HOUSING_ID);

        assertThat(response.status()).isEqualTo(InfraStatus.FRESH);
        assertThat(response.categories()).isEmpty();
    }

    @Test
    @DisplayName("TTL 이 지난 스냅샷은 STALE 이지만 이전 결과를 그대로 보여 준다")
    void returnsStaleWhenExpired() {
        givenHousing(housing(LATITUDE, LONGITUDE));
        HousingInfraSnapshot snapshot = givenSnapshot(
                Instant.now().minus(Duration.ofDays(31)), policy.signature(), LATITUDE, LONGITUDE
        );
        given(placeRepository.findAllBySnapshotIdOrderByIdAsc(snapshot.getId()))
                .willReturn(List.of(place("SUBWAY", "신설동역", 683, 6)));

        HousingInfraResponse response = service.find(HOUSING_ID);

        assertThat(response.status()).isEqualTo(InfraStatus.STALE);
        assertThat(response.categories()).hasSize(1);
    }

    @Test
    @DisplayName("수집 정책이 바뀌면 STALE 이다")
    void returnsStaleWhenPolicyChanged() {
        givenHousing(housing(LATITUDE, LONGITUDE));
        HousingInfraSnapshot snapshot = givenSnapshot(
                Instant.now(), "SUBWAY:SW8:1000", LATITUDE, LONGITUDE
        );
        given(placeRepository.findAllBySnapshotIdOrderByIdAsc(snapshot.getId())).willReturn(List.of());

        assertThat(service.find(HOUSING_ID).status()).isEqualTo(InfraStatus.STALE);
    }

    @Test
    @DisplayName("좌표가 있는데 스냅샷이 없으면 수집 대기(PENDING)다")
    void returnsPendingWhenNotCollectedYet() {
        givenHousing(housing(LATITUDE, LONGITUDE));
        given(snapshotRepository.findByHousingId(HOUSING_ID)).willReturn(Optional.empty());

        HousingInfraResponse response = service.find(HOUSING_ID);

        assertThat(response.status()).isEqualTo(InfraStatus.PENDING);
        assertThat(response.categories()).isEmpty();
        assertThat(response.collectedAt()).isNull();
    }

    @Test
    @DisplayName("좌표가 없으면 제공 불가(UNAVAILABLE)다")
    void returnsUnavailableWithoutCoordinates() {
        givenHousing(housing(null, null));

        assertThat(service.find(HOUSING_ID).status()).isEqualTo(InfraStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("모집 중이 아닌 주택은 조회할 수 없다")
    void rejectsClosedNotice() {
        Housing housing = housing(LATITUDE, LONGITUDE, NoticeStatus.CLOSED);
        given(housingRepository.findByIdAndActiveTrue(HOUSING_ID)).willReturn(Optional.of(housing));

        assertThatThrownBy(() -> service.find(HOUSING_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.HOUSE_NOT_FOUND);
    }

    private void givenHousing(Housing housing) {
        given(housingRepository.findByIdAndActiveTrue(HOUSING_ID)).willReturn(Optional.of(housing));
    }

    private HousingInfraSnapshot givenSnapshot(
            Instant collectedAt, String signature, BigDecimal latitude, BigDecimal longitude
    ) {
        HousingInfraSnapshot snapshot = new HousingInfraSnapshot(
                HOUSING_ID, signature, latitude, longitude, "{}", collectedAt
        );
        ReflectionTestUtils.setField(snapshot, "id", 10L);
        lenient().when(snapshotRepository.findByHousingId(HOUSING_ID)).thenReturn(Optional.of(snapshot));
        return snapshot;
    }

    private static Housing housing(BigDecimal latitude, BigDecimal longitude) {
        return housing(latitude, longitude, NoticeStatus.OPEN);
    }

    private static Housing housing(BigDecimal latitude, BigDecimal longitude, NoticeStatus status) {
        Notice notice = new Notice("MYHOME", "notice-1", "공고", status);
        Housing housing = new Housing(notice, "housing-1", "주택", "서울특별시 동대문구 왕산로 1");
        housing.updateCoordinates(latitude, longitude);
        ReflectionTestUtils.setField(housing, "id", HOUSING_ID);
        return housing;
    }

    private static HousingInfraPlace place(String code, String name, int distance, int searchCount) {
        return HousingInfraPlace.builder()
                .snapshotId(10L)
                .categoryCode(code)
                .name(name)
                .distanceMeter(distance)
                .searchCount(searchCount)
                .build();
    }
}
