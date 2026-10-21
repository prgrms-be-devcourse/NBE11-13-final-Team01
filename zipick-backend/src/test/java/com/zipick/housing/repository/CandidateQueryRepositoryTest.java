package com.zipick.housing.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.Expression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.zipick.housing.domain.Candidate;
import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import com.zipick.housing.dto.HouseFilter;
import com.zipick.notice.domain.NoticeStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static com.zipick.notice.domain.QAgency.agency;
import static com.zipick.notice.domain.QHousing.housing;
import static com.zipick.notice.domain.QHousingSupply.housingSupply;
import static com.zipick.notice.domain.QNotice.notice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.when;

class CandidateQueryRepositoryTest {

    private final CandidateQueryRepository repository = new CandidateQueryRepository(mock(JPAQueryFactory.class));

    @Test
    void buildsEverySupportedFilterIncludingUnknownValues() {
        HouseFilter filter = HouseFilter.builder()
                .noticeIds(Set.of(1L, 2L))
                .statuses(Set.of(NoticeStatus.OPEN, NoticeStatus.UPCOMING))
                .agencies(Set.of("LH", "SH"))
                .rentalTypes(Set.of(RentalType.values()))
                .regions(Set.of("서울특별시 강남구", "경기도"))
                .housingTypes(Set.of(HousingType.values()))
                .depositMin(100).depositMax(5000)
                .rentMin(10).rentMax(100)
                .areaMin(new BigDecimal("20.5")).areaMax(new BigDecimal("84.9"))
                .includeUnknown(true)
                .build();

        BooleanBuilder predicate = ReflectionTestUtils.invokeMethod(repository, "build", filter);

        assertThat(predicate).isNotNull();
        assertThat(predicate.toString())
                .contains("housing.active", "housingSupply.active", "notice.id", "agency.code");
    }

    @Test
    void buildsMinimalFilterWithoutUnknownValues() {
        HouseFilter filter = HouseFilter.builder().includeUnknown(false).build();

        BooleanBuilder predicate = ReflectionTestUtils.invokeMethod(repository, "build", filter);

        assertThat(predicate.toString()).contains("housing.active", "housingSupply.active");
    }

    @Test
    void mapsQueryTupleToCandidate() {
        Tuple tuple = mock(Tuple.class);
        Instant end = Instant.parse("2026-10-30T14:59:59Z");
        when(tuple.get(housingSupply.id)).thenReturn(10L);
        when(tuple.get(housing.id)).thenReturn(20L);
        when(tuple.get(notice.id)).thenReturn(30L);
        when(tuple.get(notice.title)).thenReturn("행복주택 모집공고");
        when(tuple.get(notice.noticeType)).thenReturn("행복주택");
        when(tuple.get(notice.status)).thenReturn(NoticeStatus.OPEN);
        when(tuple.get(notice.applyEndAt)).thenReturn(end);
        when(tuple.get(notice.detailUrl)).thenReturn("https://example.com/notice");
        when(tuple.get(agency.code)).thenReturn("LH");
        when(tuple.get(agency.name)).thenReturn("한국토지주택공사");
        when(tuple.get(housing.name)).thenReturn("테스트단지");
        when(tuple.get(housing.address)).thenReturn("서울 강남구 테헤란로 1");
        when(tuple.get(housing.provinceName)).thenReturn("서울특별시");
        when(tuple.get(housing.districtName)).thenReturn("강남구");
        when(tuple.get(housing.latitude)).thenReturn(new BigDecimal("37.5"));
        when(tuple.get(housing.longitude)).thenReturn(new BigDecimal("127.0"));
        when(tuple.get(housing.buildYear)).thenReturn(2020);
        when(tuple.get(housingSupply.housingType)).thenReturn("아파트");
        when(tuple.get(housingSupply.exclusiveArea)).thenReturn(new BigDecimal("39.5"));
        when(tuple.get(housingSupply.roomCount)).thenReturn(2);
        when(tuple.get(housingSupply.floor)).thenReturn(3);
        when(tuple.get(housingSupply.rentDeposit)).thenReturn(12_345_000L);
        when(tuple.get(housingSupply.monthlyRent)).thenReturn(345_000L);
        when(tuple.get(housingSupply.supplyCount)).thenReturn(4);

        Candidate candidate = ReflectionTestUtils.invokeMethod(repository, "toCandidate", tuple);

        assertThat(candidate.supplyId()).isEqualTo(10L);
        assertThat(candidate.closeDate()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(candidate.rentalType()).isEqualTo(RentalType.HAPPY);
        assertThat(candidate.housingType()).isEqualTo(HousingType.APARTMENT);
        assertThat(candidate.deposit()).isEqualTo(1235);
        assertThat(candidate.monthlyRent()).isEqualTo(35);
    }

    @Test
    void handlesEmptyHousingIdLookupAndAllDimensions() {
        assertThat(repository.findByHousingIds(List.of())).isEmpty();
        assertThat(CandidateQueryRepository.Dimension.values()).containsExactly(
                CandidateQueryRepository.Dimension.STATUS,
                CandidateQueryRepository.Dimension.AGENCY,
                CandidateQueryRepository.Dimension.RENTAL_TYPE,
                CandidateQueryRepository.Dimension.HOUSING_TYPE,
                CandidateQueryRepository.Dimension.REGION
        );
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void executesQueryEntryPointsForEveryDimension() {
        JPAQueryFactory factory = mock(JPAQueryFactory.class);
        JPAQuery<Tuple> tupleQuery = mock(JPAQuery.class, RETURNS_SELF);
        JPAQuery<Long> countQuery = mock(JPAQuery.class, RETURNS_SELF);
        when(factory.select((Expression<?>[]) any(Expression[].class))).thenReturn(tupleQuery);
        when(factory.select(housing.id.countDistinct())).thenReturn(countQuery);
        CandidateQueryRepository querying = new CandidateQueryRepository(factory);
        HouseFilter filter = HouseFilter.openOnly();

        assertThat(querying.find(filter, null, 10)).isEmpty();
        assertThat(querying.find(filter, 7L, 10)).isEmpty();
        assertThat(querying.findByHousingIds(List.of(1L, 2L))).isEmpty();
        assertThat(querying.countHousings(filter)).isZero();
        for (CandidateQueryRepository.Dimension dimension : CandidateQueryRepository.Dimension.values()) {
            assertThat(querying.countBy(filter, dimension)).isEmpty();
        }
        assertThat(querying.agencyNames()).isEmpty();
    }
}
