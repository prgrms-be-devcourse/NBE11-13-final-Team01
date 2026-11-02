package com.zipick.notice.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import com.zipick.housing.dto.HouseFilter;
import com.zipick.housing.dto.HouseFilterParams;
import com.zipick.notice.domain.Agency;
import com.zipick.notice.domain.Housing;
import com.zipick.notice.domain.Notice;
import com.zipick.notice.domain.NoticeStatus;
import com.zipick.notice.dto.MapHousingFilterPolicy;
import com.zipick.support.HousingFixtures;
import com.zipick.support.PostgresRepositoryTestBase;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * 지도 핀 조회를 실제 PostgreSQL 에서 검증한다.
 *
 * <p>Mock 테스트는 조건이 어떻게 조립되는지까지만 본다. 여기서 보려는 것은 그 조건이 실제로 돌았을 때
 * 어떤 주택이 나오느냐다. 특히 <b>서로 다른 호실의 값이 섞여 주택이 잘못 포함되지 않는지</b>는
 * 진짜 DB 에서만 드러난다.
 */
@Import(HousingPinQueryRepository.class)
class HousingPinQueryRepositoryIntegrationTest extends PostgresRepositoryTestBase {

    @Autowired
    private HousingPinQueryRepository repository;

    @Autowired
    private EntityManager em;

    private HousingFixtures fixtures;
    private Agency lh;
    private Agency sh;

    @BeforeEach
    void setUp() {
        fixtures = new HousingFixtures(em);
        lh = fixtures.agency("LH", "한국토지주택공사");
        sh = fixtures.agency("SH", "서울주택도시공사");
    }

    private List<Long> pinIds(HouseFilter filter) {
        fixtures.flush();
        return repository.findPins(filter).stream().map(Housing::getId).toList();
    }

    private HouseFilter mapFilter(HouseFilterParams params) {
        return MapHousingFilterPolicy.apply(params);
    }

    private HouseFilter defaultMapFilter() {
        return mapFilter(new HouseFilterParams());
    }

    @Nested
    @DisplayName("기본 조회")
    class BasicQuery {

        @Test
        @DisplayName("공고 예정과 접수 중 주택을 돌려주고 마감·취소는 제외한다")
        void returnsUpcomingAndOpenOnly() {
            Housing upcoming = fixtures.housing(fixtures.notice(lh, NoticeStatus.UPCOMING, "공고예정", null), "A", "서울특별시", "강남구");
            Housing open = fixtures.housing(fixtures.notice(lh, NoticeStatus.OPEN, "접수중", null), "B", "서울특별시", "강남구");
            fixtures.housing(fixtures.notice(lh, NoticeStatus.CLOSED, "마감", null), "C", "서울특별시", "강남구");
            fixtures.housing(fixtures.notice(lh, NoticeStatus.CANCELLED, "취소", null), "D", "서울특별시", "강남구");

            assertThat(pinIds(defaultMapFilter())).containsExactlyInAnyOrder(upcoming.getId(), open.getId());
        }

        @Test
        @DisplayName("비활성 주택은 제외한다")
        void excludesInactiveHousing() {
            Notice notice = fixtures.notice(lh, NoticeStatus.OPEN, "접수중", null);
            Housing active = fixtures.housing(notice, "활성", "서울특별시", "강남구");
            fixtures.inactiveHousing(notice, "비활성");

            assertThat(pinIds(defaultMapFilter())).containsExactly(active.getId());
        }

        @Test
        @DisplayName("좌표가 없는 주택은 핀을 만들 수 없어 제외한다")
        void excludesHousingWithoutCoordinates() {
            Notice notice = fixtures.notice(lh, NoticeStatus.OPEN, "접수중", null);
            Housing located = fixtures.housing(notice, "좌표있음", "서울특별시", "강남구");
            fixtures.housingWithoutCoordinates(notice, "좌표없음");

            assertThat(pinIds(defaultMapFilter())).containsExactly(located.getId());
        }

        @Test
        @DisplayName("활성 공급 정보가 없어도 유효한 공고의 주택은 기본 지도에 나온다")
        void includesHousingWithoutAnyActiveSupply() {
            Notice notice = fixtures.notice(lh, NoticeStatus.OPEN, "접수중", null);
            Housing noSupply = fixtures.housing(notice, "공급정보없음", "서울특별시", "강남구");
            Housing inactiveSupplyOnly = fixtures.housing(notice, "비활성공급만", "서울특별시", "강남구");
            fixtures.supply(inactiveSupplyOnly, 1000, 30, "26.0", "아파트", false);

            assertThat(pinIds(defaultMapFilter()))
                    .containsExactlyInAnyOrder(noSupply.getId(), inactiveSupplyOnly.getId());
        }

        @Test
        @DisplayName("호실이 여러 개여도 주택당 한 건만 나온다")
        void returnsOneRowPerHousingRegardlessOfSupplyCount() {
            Notice notice = fixtures.notice(lh, NoticeStatus.OPEN, "접수중", null);
            Housing housing = fixtures.housing(notice, "여러호실", "서울특별시", "강남구");
            fixtures.supply(housing, 1000, 20, "26.0", "아파트", true);
            fixtures.supply(housing, 2000, 30, "36.0", "아파트", true);
            fixtures.supply(housing, 3000, 40, "46.0", "아파트", true);

            assertThat(pinIds(defaultMapFilter())).containsExactly(housing.getId());
        }
    }

    @Nested
    @DisplayName("같은 호실 기준 공급 조건 판정")
    class SameSupplyRowFiltering {

        @Test
        @DisplayName("한 호실이 모든 조건을 만족하면 주택을 포함한다")
        void includesWhenOneSupplySatisfiesEveryCondition() {
            Notice notice = fixtures.notice(lh, NoticeStatus.OPEN, "접수중", null);
            Housing housingA = fixtures.housing(notice, "A", "서울특별시", "강남구");
            fixtures.supply(housingA, 1000, 20, "26.0", "아파트", true);   // 보증금만 만족
            fixtures.supply(housingA, 2000, 25, "36.0", "아파트", true);   // 둘 다 만족

            HouseFilterParams params = new HouseFilterParams();
            params.setDepositMax(2500);
            params.setAreaMin(new BigDecimal("30"));

            assertThat(pinIds(mapFilter(params))).containsExactly(housingA.getId());
        }

        @Test
        @DisplayName("서로 다른 호실이 조건을 하나씩만 만족하면 주택을 제외한다")
        void excludesWhenConditionsAreSplitAcrossDifferentSupplies() {
            Notice notice = fixtures.notice(lh, NoticeStatus.OPEN, "접수중", null);
            Housing housingB = fixtures.housing(notice, "B", "서울특별시", "강남구");
            fixtures.supply(housingB, 1000, 20, "20.0", "아파트", true);   // 보증금만 만족 (면적 미달)
            fixtures.supply(housingB, 3000, 25, "40.0", "아파트", true);   // 면적만 만족 (보증금 초과)

            HouseFilterParams params = new HouseFilterParams();
            params.setDepositMax(2500);
            params.setAreaMin(new BigDecimal("30"));

            assertThat(pinIds(mapFilter(params)))
                    .as("서로 다른 호실의 값을 조합해 포함하면 안 된다")
                    .isEmpty();
        }

        @Test
        @DisplayName("두 주택을 함께 두면 조건을 만족하는 쪽만 나온다")
        void picksOnlyTheHousingWithASatisfyingSupply() {
            Notice notice = fixtures.notice(lh, NoticeStatus.OPEN, "접수중", null);
            Housing housingA = fixtures.housing(notice, "A", "서울특별시", "강남구");
            fixtures.supply(housingA, 1000, 20, "26.0", "아파트", true);
            fixtures.supply(housingA, 2000, 25, "36.0", "아파트", true);
            Housing housingB = fixtures.housing(notice, "B", "서울특별시", "강남구");
            fixtures.supply(housingB, 1000, 20, "20.0", "아파트", true);
            fixtures.supply(housingB, 3000, 25, "40.0", "아파트", true);

            HouseFilterParams params = new HouseFilterParams();
            params.setDepositMax(2500);
            params.setAreaMin(new BigDecimal("30"));

            assertThat(pinIds(mapFilter(params))).containsExactly(housingA.getId());
        }

        @Test
        @DisplayName("조건을 만족하는 호실이 비활성이면 제외한다")
        void ignoresInactiveSupplyEvenIfItMatches() {
            Notice notice = fixtures.notice(lh, NoticeStatus.OPEN, "접수중", null);
            Housing housing = fixtures.housing(notice, "비활성만족", "서울특별시", "강남구");
            fixtures.supply(housing, 2000, 25, "36.0", "아파트", false);

            HouseFilterParams params = new HouseFilterParams();
            params.setDepositMax(2500);
            params.setAreaMin(new BigDecimal("30"));

            assertThat(pinIds(mapFilter(params))).isEmpty();
        }

        @Test
        @DisplayName("공급 정보가 아예 없으면 공급 조건 조회에서 제외한다")
        void excludesHousingWithoutSupplyWhenSupplyFilterIsSelected() {
            Notice notice = fixtures.notice(lh, NoticeStatus.OPEN, "접수중", null);
            fixtures.housing(notice, "공급정보없음", "서울특별시", "강남구");

            HouseFilterParams params = new HouseFilterParams();
            params.setDepositMax(2500);

            assertThat(pinIds(mapFilter(params))).isEmpty();
        }

        @Test
        @DisplayName("공고·주택 조건만 고르면 공급 정보가 없어도 나온다")
        void noticeOnlyFilterKeepsHousingWithoutSupply() {
            Notice notice = fixtures.notice(lh, NoticeStatus.OPEN, "접수중", null);
            Housing housing = fixtures.housing(notice, "공급정보없음", "서울특별시", "강남구");

            HouseFilterParams params = new HouseFilterParams();
            params.setRegions(List.of("서울특별시 강남구"));
            params.setAgencies(List.of("LH"));

            assertThat(pinIds(mapFilter(params))).containsExactly(housing.getId());
        }
    }

    @Nested
    @DisplayName("값이 없는 공급 정보 (엄격 판정)")
    class NullHandling {

        @Test
        @DisplayName("보증금 조건을 고르면 보증금이 없는 호실은 조건을 만족하지 않는다")
        void nullDepositFailsSelectedDepositFilter() {
            Notice notice = fixtures.notice(lh, NoticeStatus.OPEN, "접수중", null);
            Housing housing = fixtures.housing(notice, "보증금없음", "서울특별시", "강남구");
            fixtures.supply(housing, null, 25, "36.0", "아파트", true);

            assertThat(pinIds(defaultMapFilter())).as("조건을 안 고르면 그대로 나온다").containsExactly(housing.getId());

            HouseFilterParams params = new HouseFilterParams();
            params.setDepositMax(2500);
            assertThat(pinIds(mapFilter(params))).isEmpty();
        }

        @Test
        @DisplayName("면적 조건을 고르면 면적이 없는 호실은 조건을 만족하지 않는다")
        void nullAreaFailsSelectedAreaFilter() {
            Notice notice = fixtures.notice(lh, NoticeStatus.OPEN, "접수중", null);
            Housing housing = fixtures.housing(notice, "면적없음", "서울특별시", "강남구");
            fixtures.supply(housing, 1000, 25, null, "아파트", true);

            HouseFilterParams params = new HouseFilterParams();
            params.setAreaMin(new BigDecimal("20"));

            assertThat(pinIds(mapFilter(params))).isEmpty();
        }

        @Test
        @DisplayName("includeUnknown=true 로 요청해도 엄격 판정을 우회할 수 없다")
        void includeUnknownCannotBypassMapPolicy() {
            Notice notice = fixtures.notice(lh, NoticeStatus.OPEN, "접수중", null);
            Housing housing = fixtures.housing(notice, "월세없음", "서울특별시", "강남구");
            fixtures.supply(housing, 1000, null, "36.0", "아파트", true);

            HouseFilterParams params = new HouseFilterParams();
            params.setRentMax(50);
            params.setIncludeUnknown(true);

            assertThat(pinIds(mapFilter(params))).isEmpty();
        }

        @Test
        @DisplayName("여러 조건을 고르면 한 호실이 모든 값을 갖고 모두 만족해야 한다")
        void everySelectedConditionMustBeSatisfiedBySameSupply() {
            Notice notice = fixtures.notice(lh, NoticeStatus.OPEN, "접수중", null);
            Housing partial = fixtures.housing(notice, "일부값없음", "서울특별시", "강남구");
            fixtures.supply(partial, 1000, null, "36.0", "아파트", true);
            Housing complete = fixtures.housing(notice, "모두있음", "서울특별시", "강남구");
            fixtures.supply(complete, 1000, 30, "36.0", "아파트", true);

            HouseFilterParams params = new HouseFilterParams();
            params.setDepositMax(2500);
            params.setRentMax(50);
            params.setAreaMin(new BigDecimal("30"));

            assertThat(pinIds(mapFilter(params))).containsExactly(complete.getId());
        }
    }

    @Nested
    @DisplayName("모집 상태 요청")
    class StatusFiltering {

        private Housing upcoming;
        private Housing open;

        @BeforeEach
        void given() {
            upcoming = fixtures.housing(fixtures.notice(lh, NoticeStatus.UPCOMING, "공고예정", null), "U", "서울특별시", "강남구");
            open = fixtures.housing(fixtures.notice(lh, NoticeStatus.OPEN, "접수중", null), "O", "서울특별시", "강남구");
            fixtures.housing(fixtures.notice(lh, NoticeStatus.CLOSED, "마감", null), "C", "서울특별시", "강남구");
        }

        @Test
        @DisplayName("미선택이면 공고 예정 + 접수 중")
        void defaultShowsBothVisibleStatuses() {
            assertThat(pinIds(defaultMapFilter())).containsExactlyInAnyOrder(upcoming.getId(), open.getId());
        }

        @Test
        @DisplayName("OPEN 을 고르면 접수 중만")
        void openOnly() {
            assertThat(pinIds(statusFilter("OPEN"))).containsExactly(open.getId());
        }

        @Test
        @DisplayName("UPCOMING 을 고르면 공고 예정만")
        void upcomingOnly() {
            assertThat(pinIds(statusFilter("UPCOMING"))).containsExactly(upcoming.getId());
        }

        @Test
        @DisplayName("CLOSED 만 요청하면 빈 결과다 (상태 조건이 사라져 전체가 나오면 안 된다)")
        void closedOnlyReturnsNothingNotEverything() {
            assertThat(pinIds(statusFilter("CLOSED"))).isEmpty();
        }

        @Test
        @DisplayName("CANCELLED 만 요청해도 빈 결과다")
        void cancelledOnlyReturnsNothing() {
            assertThat(pinIds(statusFilter("CANCELLED"))).isEmpty();
        }

        @Test
        @DisplayName("OPEN + CLOSED 를 요청하면 접수 중만 나온다")
        void mixedRequestKeepsOnlyVisibleStatus() {
            assertThat(pinIds(statusFilter("OPEN", "CLOSED"))).containsExactly(open.getId());
        }

        private HouseFilter statusFilter(String... statuses) {
            HouseFilterParams params = new HouseFilterParams();
            params.setStatuses(List.of(statuses));
            return mapFilter(params);
        }
    }

    @Nested
    @DisplayName("필터별 실제 조회")
    class EachFilter {

        @Test
        @DisplayName("지역 — 시도 + 시군구로 거른다")
        void regionFilter() {
            Notice notice = fixtures.notice(lh, NoticeStatus.OPEN, "접수중", null);
            Housing gangnam = fixtures.housing(notice, "강남", "서울특별시", "강남구");
            fixtures.housing(notice, "마포", "서울특별시", "마포구");

            HouseFilterParams params = new HouseFilterParams();
            params.setRegions(List.of("서울특별시 강남구"));

            assertThat(pinIds(mapFilter(params))).containsExactly(gangnam.getId());
        }

        @Test
        @DisplayName("공급기관 — 기관 코드로 거른다")
        void agencyFilter() {
            Housing lhHousing = fixtures.housing(fixtures.notice(lh, NoticeStatus.OPEN, "LH 공고", null), "LH", "서울특별시", "강남구");
            fixtures.housing(fixtures.notice(sh, NoticeStatus.OPEN, "SH 공고", null), "SH", "서울특별시", "강남구");

            HouseFilterParams params = new HouseFilterParams();
            params.setAgencies(List.of("LH"));

            assertThat(pinIds(mapFilter(params))).containsExactly(lhHousing.getId());
        }

        @Test
        @DisplayName("공공임대 유형 — 공고 유형으로 판정한다 (공급 정보가 아니다)")
        void rentalTypeIsJudgedOnNotice() {
            Housing happy = fixtures.housing(
                    fixtures.notice(lh, NoticeStatus.OPEN, "2026년 입주자 모집", "행복주택"), "행복", "서울특별시", "강남구");
            fixtures.housing(
                    fixtures.notice(lh, NoticeStatus.OPEN, "2026년 입주자 모집", "국민임대"), "국민", "서울특별시", "강남구");

            HouseFilterParams params = new HouseFilterParams();
            params.setRentalTypes(List.of(RentalType.HAPPY.name()));

            assertThat(pinIds(mapFilter(params))).containsExactly(happy.getId());
        }

        @Test
        @DisplayName("공공임대 유형 — 공고 유형이 비면 제목으로 판정한다")
        void rentalTypeFallsBackToNoticeTitle() {
            Housing happy = fixtures.housing(
                    fixtures.notice(lh, NoticeStatus.OPEN, "행복주택 입주자 모집", null), "행복", "서울특별시", "강남구");
            fixtures.housing(
                    fixtures.notice(lh, NoticeStatus.OPEN, "국민임대 입주자 모집", null), "국민", "서울특별시", "강남구");

            HouseFilterParams params = new HouseFilterParams();
            params.setRentalTypes(List.of(RentalType.HAPPY.name()));

            assertThat(pinIds(mapFilter(params))).containsExactly(happy.getId());
        }

        @Test
        @DisplayName("주택 유형 — 공급 호실의 원본 문자열로 판정한다 (주택이 아니다)")
        void housingTypeIsJudgedOnSupply() {
            Notice notice = fixtures.notice(lh, NoticeStatus.OPEN, "접수중", null);
            Housing apartment = fixtures.housing(notice, "아파트", "서울특별시", "강남구");
            fixtures.supply(apartment, 1000, 20, "26.0", "아파트", true);
            Housing officetel = fixtures.housing(notice, "오피스텔", "서울특별시", "강남구");
            fixtures.supply(officetel, 1000, 20, "26.0", "오피스텔", true);

            HouseFilterParams params = new HouseFilterParams();
            params.setHousingTypes(List.of(HousingType.APARTMENT.name()));

            assertThat(pinIds(mapFilter(params))).containsExactly(apartment.getId());
        }

        @Test
        @DisplayName("보증금 — 만 원 단위 요청이 원 단위 컬럼과 맞는다")
        void depositRangeFilter() {
            Notice notice = fixtures.notice(lh, NoticeStatus.OPEN, "접수중", null);
            Housing cheap = fixtures.housing(notice, "저렴", "서울특별시", "강남구");
            fixtures.supply(cheap, 1000, 20, "26.0", "아파트", true);
            Housing pricey = fixtures.housing(notice, "비쌈", "서울특별시", "강남구");
            fixtures.supply(pricey, 5000, 20, "26.0", "아파트", true);

            HouseFilterParams params = new HouseFilterParams();
            params.setDepositMin(500);
            params.setDepositMax(2000);

            assertThat(pinIds(mapFilter(params))).containsExactly(cheap.getId());
        }

        @Test
        @DisplayName("월 임대료 범위")
        void rentRangeFilter() {
            Notice notice = fixtures.notice(lh, NoticeStatus.OPEN, "접수중", null);
            Housing low = fixtures.housing(notice, "저월세", "서울특별시", "강남구");
            fixtures.supply(low, 1000, 20, "26.0", "아파트", true);
            Housing high = fixtures.housing(notice, "고월세", "서울특별시", "강남구");
            fixtures.supply(high, 1000, 90, "26.0", "아파트", true);

            HouseFilterParams params = new HouseFilterParams();
            params.setRentMax(50);

            assertThat(pinIds(mapFilter(params))).containsExactly(low.getId());
        }

        @Test
        @DisplayName("전용면적 범위")
        void areaRangeFilter() {
            Notice notice = fixtures.notice(lh, NoticeStatus.OPEN, "접수중", null);
            Housing small = fixtures.housing(notice, "소형", "서울특별시", "강남구");
            fixtures.supply(small, 1000, 20, "26.0", "아파트", true);
            Housing large = fixtures.housing(notice, "대형", "서울특별시", "강남구");
            fixtures.supply(large, 1000, 20, "59.0", "아파트", true);

            HouseFilterParams params = new HouseFilterParams();
            params.setAreaMin(new BigDecimal("40"));
            params.setAreaMax(new BigDecimal("85"));

            assertThat(pinIds(mapFilter(params))).containsExactly(large.getId());
        }

        @Test
        @DisplayName("여러 필터를 함께 걸면 모두 만족하는 주택만 나온다")
        void combinedFilters() {
            Notice lhNotice = fixtures.notice(lh, NoticeStatus.OPEN, "행복주택 모집", "행복주택");
            Housing match = fixtures.housing(lhNotice, "정답", "서울특별시", "강남구");
            fixtures.supply(match, 1500, 30, "40.0", "아파트", true);

            Housing wrongRegion = fixtures.housing(lhNotice, "지역다름", "서울특별시", "마포구");
            fixtures.supply(wrongRegion, 1500, 30, "40.0", "아파트", true);

            Housing wrongDeposit = fixtures.housing(lhNotice, "보증금초과", "서울특별시", "강남구");
            fixtures.supply(wrongDeposit, 9000, 30, "40.0", "아파트", true);

            Notice shNotice = fixtures.notice(sh, NoticeStatus.OPEN, "국민임대 모집", "국민임대");
            Housing wrongAgency = fixtures.housing(shNotice, "기관다름", "서울특별시", "강남구");
            fixtures.supply(wrongAgency, 1500, 30, "40.0", "아파트", true);

            HouseFilterParams params = new HouseFilterParams();
            params.setRegions(List.of("서울특별시 강남구"));
            params.setAgencies(List.of("LH"));
            params.setRentalTypes(List.of(RentalType.HAPPY.name()));
            params.setHousingTypes(List.of(HousingType.APARTMENT.name()));
            params.setDepositMax(2000);
            params.setRentMax(50);
            params.setAreaMin(new BigDecimal("30"));

            assertThat(pinIds(mapFilter(params))).containsExactly(match.getId());
        }
    }

    @Nested
    @DisplayName("조회 상한")
    class NoSupplyCountLimit {

        @Test
        @DisplayName("호실이 아주 많아도 주택이 잘리지 않는다")
        void housingCountIsNotLimitedBySupplyRows() {
            Notice notice = fixtures.notice(lh, NoticeStatus.OPEN, "접수중", null);
            // 전체 목록 API 는 내부에서 호실 10,000건만 읽어 주택으로 묶는다.
            // 지도는 주택 행만 읽으므로 호실 수가 많아도 주택이 사라지지 않아야 한다.
            for (int i = 0; i < 20; i++) {
                Housing housing = fixtures.housing(notice, "주택" + i, "서울특별시", "강남구");
                for (int j = 0; j < 30; j++) {
                    fixtures.supply(housing, 1000 + j, 20, "26.0", "아파트", true);
                }
            }

            assertThat(pinIds(defaultMapFilter())).hasSize(20);
        }
    }

    @Nested
    @DisplayName("빈 상태 집합")
    class EmptyStatusSet {

        @Test
        @DisplayName("상태 집합이 비면 쿼리하지 않고 빈 결과다")
        void emptyStatusMeansEmptyResult() {
            fixtures.housing(fixtures.notice(lh, NoticeStatus.OPEN, "접수중", null), "O", "서울특별시", "강남구");

            HouseFilter noStatus = HouseFilter.builder().statuses(Set.of()).includeUnknown(false).build();

            assertThat(pinIds(noStatus)).isEmpty();
        }
    }
}
