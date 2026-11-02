package com.zipick.housing.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.zipick.housing.domain.Candidate;
import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import com.zipick.housing.dto.HouseFilter;
import com.zipick.notice.domain.Agency;
import com.zipick.notice.domain.Housing;
import com.zipick.notice.domain.HousingSupply;
import com.zipick.notice.domain.Notice;
import com.zipick.notice.domain.NoticeStatus;
import com.zipick.support.HousingFixtures;
import com.zipick.support.PostgresRepositoryTestBase;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * M1(필터 판정식을 HouseFilterPredicates 로 분리) 이후에도 추천 후보 조회가 그대로인지 실제 DB 에서 본다.
 *
 * <p>Predicate 문자열 동등성(HouseFilterPredicatesEquivalenceTest)은 "같은 조건이 만들어진다"를 보여 주고,
 * 여기서는 "그 조건으로 실제 조회했을 때 같은 행이 나온다"를 본다.
 * 변경 전 구현({@link LegacyCandidateFilterFixture})을 직접 걸어 조회한 결과와 비교한다.
 *
 * <p>후보 조회 결과가 같다는 것까지가 여기서 직접 확인하는 범위다.
 * 점수·순위·정보 부족 그룹은 이 후보를 입력으로 삼는 별도 단위 테스트가 담당한다.
 */
@Import(CandidateQueryRepository.class)
class CandidateQueryRepositoryIntegrationTest extends PostgresRepositoryTestBase {

    @Autowired
    private CandidateQueryRepository repository;

    @Autowired
    private com.querydsl.jpa.impl.JPAQueryFactory queryFactory;

    @Autowired
    private EntityManager em;

    private HousingFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new HousingFixtures(em);
        Agency lh = fixtures.agency("LH", "한국토지주택공사");
        Agency sh = fixtures.agency("SH", "서울주택도시공사");

        Notice happy = fixtures.notice(lh, NoticeStatus.OPEN, "행복주택 입주자 모집", "행복주택");
        Housing gangnam = fixtures.housing(happy, "강남단지", "서울특별시", "강남구");
        fixtures.supply(gangnam, 1000, 20, "26.0", "아파트", true);
        fixtures.supply(gangnam, 2000, 30, "36.0", "아파트", true);
        fixtures.supply(gangnam, 9000, 90, "59.0", "오피스텔", true);
        fixtures.supply(gangnam, 1500, 25, "30.0", "아파트", false);     // 비활성
        fixtures.supply(gangnam, null, null, null, null, true);          // 값 없음

        Notice national = fixtures.notice(sh, NoticeStatus.UPCOMING, "국민임대 입주자 모집", "국민임대");
        Housing mapo = fixtures.housing(national, "마포단지", "서울특별시", "마포구");
        fixtures.supply(mapo, 3000, 40, "46.0", "연립", true);
        Housing noCoordinates = fixtures.housingWithoutCoordinates(national, "좌표없음단지");
        fixtures.supply(noCoordinates, 1200, 22, "28.0", "아파트", true);

        Notice closed = fixtures.notice(lh, NoticeStatus.CLOSED, "마감된 모집", "행복주택");
        Housing closedHousing = fixtures.housing(closed, "마감단지", "서울특별시", "강남구");
        fixtures.supply(closedHousing, 1000, 20, "26.0", "아파트", true);

        Housing inactive = fixtures.inactiveHousing(happy, "비활성단지");
        fixtures.supply(inactive, 1000, 20, "26.0", "아파트", true);

        fixtures.flush();
    }

    /** 변경 전 판정식으로 직접 조회해 비교 기준을 만든다. */
    private List<Long> legacySupplyIds(HouseFilter filter) {
        return queryFactory.select(com.zipick.notice.domain.QHousingSupply.housingSupply.id)
                .from(com.zipick.notice.domain.QHousingSupply.housingSupply)
                .join(com.zipick.notice.domain.QHousingSupply.housingSupply.housing,
                        com.zipick.notice.domain.QHousing.housing)
                .join(com.zipick.notice.domain.QHousing.housing.notice, com.zipick.notice.domain.QNotice.notice)
                .leftJoin(com.zipick.notice.domain.QNotice.notice.agency, com.zipick.notice.domain.QAgency.agency)
                .where(LegacyCandidateFilterFixture.build(filter))
                .orderBy(com.zipick.notice.domain.QHousingSupply.housingSupply.id.asc())
                .fetch();
    }

    private List<Long> currentSupplyIds(HouseFilter filter) {
        return repository.find(filter, null, 10_000).stream()
                .map(Candidate::supplyId)
                .sorted()
                .toList();
    }

    @DisplayName("M1 전후로 실제 조회되는 공급 호실이 같다")
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("filters")
    void returnsSameSuppliesAsBeforeRefactoring(String name, HouseFilter filter) {
        List<Long> before = legacySupplyIds(filter);
        List<Long> after = currentSupplyIds(filter);

        assertThat(after).as("필터: %s", name).containsExactlyElementsOf(before);
    }

    @DisplayName("M1 전후로 실제 조회되는 주택이 같다")
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("filters")
    void returnsSameHousingsAsBeforeRefactoring(String name, HouseFilter filter) {
        List<Long> beforeHousings = queryFactory
                .select(com.zipick.notice.domain.QHousingSupply.housingSupply.housing.id).distinct()
                .from(com.zipick.notice.domain.QHousingSupply.housingSupply)
                .join(com.zipick.notice.domain.QHousingSupply.housingSupply.housing,
                        com.zipick.notice.domain.QHousing.housing)
                .join(com.zipick.notice.domain.QHousing.housing.notice, com.zipick.notice.domain.QNotice.notice)
                .leftJoin(com.zipick.notice.domain.QNotice.notice.agency, com.zipick.notice.domain.QAgency.agency)
                .where(LegacyCandidateFilterFixture.build(filter))
                .fetch().stream().sorted().toList();

        List<Long> afterHousings = repository.find(filter, null, 10_000).stream()
                .map(Candidate::housingId).distinct().sorted().toList();

        assertThat(afterHousings).as("필터: %s", name).containsExactlyElementsOf(beforeHousings);
    }

    @Test
    @DisplayName("추천 후보는 활성 호실만 본다 (기존 정책 유지)")
    void keepsActiveSupplyOnlyPolicy() {
        List<HousingSupply> inactiveSupplies = em.createQuery(
                "select s from HousingSupply s where s.active = false", HousingSupply.class).getResultList();
        assertThat(inactiveSupplies).as("비활성 호실이 있는 상태에서 비교해야 의미가 있다").isNotEmpty();

        List<Long> candidateSupplyIds = currentSupplyIds(HouseFilter.openOnly());

        assertThat(candidateSupplyIds)
                .doesNotContainAnyElementsOf(inactiveSupplies.stream().map(HousingSupply::getId).toList());
    }

    @Test
    @DisplayName("추천 후보는 좌표가 없어도 포함한다 — 지도 조회와 역할이 다르다")
    void candidatesIncludeHousingWithoutCoordinates() {
        List<Candidate> candidates = repository.find(HouseFilter.openOnly(), null, 10_000);

        assertThat(candidates)
                .as("좌표는 추천 점수에 직접 쓰이지 않는다. 지도만 좌표를 요구한다")
                .anyMatch(c -> c.lat() == null && c.lng() == null);
    }

    @Test
    @DisplayName("includeUnknown=true 면 값이 없는 호실도 후보에 남는다 (기존 정책 유지)")
    void lenientNullPolicyIsUnchangedForCandidates() {
        HouseFilter lenient = HouseFilter.builder()
                .statuses(HouseFilter.OPEN_STATUSES).depositMax(2500).includeUnknown(true).build();
        HouseFilter strict = lenient.toBuilder().includeUnknown(false).build();

        List<Long> lenientIds = currentSupplyIds(lenient);
        List<Long> strictIds = currentSupplyIds(strict);

        assertThat(lenientIds).as("값 없는 호실이 포함되어 더 많아야 한다").hasSizeGreaterThan(strictIds.size());
        assertThat(lenientIds).containsAll(strictIds);
    }

    static List<Object[]> filters() {
        return List.of(
                new Object[]{"기본(openOnly)", HouseFilter.openOnly()},
                new Object[]{"접수 중만", HouseFilter.builder()
                        .statuses(Set.of(NoticeStatus.OPEN)).includeUnknown(true).build()},
                new Object[]{"마감 포함", HouseFilter.builder()
                        .statuses(Set.of(NoticeStatus.OPEN, NoticeStatus.CLOSED)).includeUnknown(true).build()},
                new Object[]{"지역", HouseFilter.builder()
                        .statuses(HouseFilter.OPEN_STATUSES).regions(Set.of("서울특별시 강남구")).includeUnknown(true).build()},
                new Object[]{"기관", HouseFilter.builder()
                        .statuses(HouseFilter.OPEN_STATUSES).agencies(Set.of("LH")).includeUnknown(true).build()},
                new Object[]{"임대유형", HouseFilter.builder()
                        .statuses(HouseFilter.OPEN_STATUSES).rentalTypes(Set.of(RentalType.HAPPY)).includeUnknown(true).build()},
                new Object[]{"주택유형(unknown 포함)", HouseFilter.builder()
                        .statuses(HouseFilter.OPEN_STATUSES).housingTypes(Set.of(HousingType.APARTMENT)).includeUnknown(true).build()},
                new Object[]{"주택유형(unknown 제외)", HouseFilter.builder()
                        .statuses(HouseFilter.OPEN_STATUSES).housingTypes(Set.of(HousingType.APARTMENT)).includeUnknown(false).build()},
                new Object[]{"보증금(unknown 포함)", HouseFilter.builder()
                        .statuses(HouseFilter.OPEN_STATUSES).depositMax(2500).includeUnknown(true).build()},
                new Object[]{"보증금(unknown 제외)", HouseFilter.builder()
                        .statuses(HouseFilter.OPEN_STATUSES).depositMax(2500).includeUnknown(false).build()},
                new Object[]{"월세", HouseFilter.builder()
                        .statuses(HouseFilter.OPEN_STATUSES).rentMax(50).includeUnknown(false).build()},
                new Object[]{"면적", HouseFilter.builder()
                        .statuses(HouseFilter.OPEN_STATUSES).areaMin(new BigDecimal("30")).includeUnknown(false).build()},
                new Object[]{"전체 조합", HouseFilter.builder()
                        .statuses(HouseFilter.OPEN_STATUSES)
                        .regions(Set.of("서울특별시 강남구"))
                        .agencies(Set.of("LH"))
                        .rentalTypes(Set.of(RentalType.HAPPY))
                        .housingTypes(Set.of(HousingType.APARTMENT))
                        .depositMin(500).depositMax(2500)
                        .rentMax(50)
                        .areaMin(new BigDecimal("25")).areaMax(new BigDecimal("40"))
                        .includeUnknown(true).build()}
        );
    }
}
