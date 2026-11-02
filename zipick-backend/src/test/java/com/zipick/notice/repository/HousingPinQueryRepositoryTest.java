package com.zipick.notice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.querydsl.core.types.Predicate;
import com.querydsl.jpa.JPQLSerializer;
import com.querydsl.jpa.JPQLTemplates;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.zipick.housing.domain.HousingType;
import com.zipick.housing.dto.HouseFilter;
import com.zipick.notice.domain.NoticeStatus;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 지도 핀 조회의 조건 구성. DB 없이 WHERE 절 구조만 검증한다
 * (이 프로젝트에는 아직 테스트용 DB가 없어 JOIN/EXISTS 실행 결과는 수동 확인 대상이다).
 */
class HousingPinQueryRepositoryTest {

    private final JPAQueryFactory queryFactory = mock(JPAQueryFactory.class);
    private final HousingPinQueryRepository repository = new HousingPinQueryRepository(queryFactory);

    @Test
    @DisplayName("상태 교집합이 비면 쿼리하지 않고 빈 결과를 돌려준다")
    void emptyStatusMeansNoResultsNotAllResults() {
        HouseFilter noVisibleStatus = HouseFilter.builder().statuses(Set.of()).includeUnknown(false).build();

        assertThat(repository.findPins(noVisibleStatus)).isEmpty();
        // 조건을 빼고 조회하면 전체 주택이 나온다. 쿼리 자체를 하지 않아야 한다.
        verifyNoInteractions(queryFactory);
    }

    @Test
    @DisplayName("기본 조회 조건은 활성 주택과 좌표 보유다")
    void alwaysRequiresActiveHousingWithCoordinates() {
        String where = jpql(repository.pinWhere(open()));

        assertThat(where).contains("housing.active = ");
        assertThat(where).contains("housing.latitude is not null");
        assertThat(where).contains("housing.longitude is not null");
    }

    @Test
    @DisplayName("공급 조건을 고르지 않으면 EXISTS 를 걸지 않는다 (공급 정보가 없어도 표시)")
    void noSupplyFilterMeansNoExists() {
        HouseFilter onlyNoticeConditions = open().toBuilder()
                .regions(Set.of("서울특별시 마포구"))
                .agencies(Set.of("LH"))
                .build();

        assertThat(repository.activeSupplyExists(onlyNoticeConditions)).isNull();
        assertThat(jpql(repository.pinWhere(onlyNoticeConditions))).doesNotContain("exists");
    }

    @Test
    @DisplayName("공급 조건을 고르면 활성 호실 하나가 조건을 모두 만족해야 한다")
    void supplyFilterBecomesExistsOnSameActiveSupply() {
        HouseFilter withSupplyConditions = open().toBuilder()
                .depositMax(2500)
                .areaMin(new BigDecimal("30"))
                .housingTypes(Set.of(HousingType.APARTMENT))
                .build();

        String where = jpql(repository.pinWhere(withSupplyConditions));

        assertThat(where).contains("exists");
        // 같은 서브쿼리 안에서 호실을 주택에 묶고, 활성 호실만 보고, 조건을 모두 AND 로 건다.
        assertThat(where).contains("housingSupply.housing = housing");
        assertThat(where).contains("housingSupply.active = ");
        assertThat(where).contains("housingSupply.rentDeposit");
        assertThat(where).contains("housingSupply.exclusiveArea");
        assertThat(where).contains("housingSupply.housingType");
    }

    @Test
    @DisplayName("공급 조건은 EXISTS 안에만 있어 주택이 호실 수만큼 중복되지 않는다")
    void supplyConditionsStayInsideExists() {
        HouseFilter withSupplyConditions = open().toBuilder().depositMax(2500).build();
        String where = jpql(repository.pinWhere(withSupplyConditions));

        // 바깥 WHERE 에 housingSupply 가 직접 등장하면 조인이 생겨 주택이 중복된다.
        String outsideExists = where.substring(0, where.indexOf("exists"));
        assertThat(outsideExists).doesNotContain("housingSupply");
    }

    @Test
    @DisplayName("지도 필터는 값이 없는 호실을 조건 충족으로 보지 않는다")
    void strictNullHandlingForSelectedSupplyConditions() {
        HouseFilter strict = open().toBuilder().depositMax(2500).includeUnknown(false).build();

        assertThat(jpql(repository.pinWhere(strict))).doesNotContain("rentDeposit is null");
    }

    /**
     * BooleanBuilder.toString() 은 EXISTS 서브쿼리를 "exists ...QueryMetadata@..." 로만 찍는다.
     * 서브쿼리 안쪽까지 보려면 실제 JPQL 로 직렬화해야 한다.
     */
    private String jpql(Predicate predicate) {
        // 상수는 ?1, ?2 … 바인딩 파라미터로 바뀐다. 여기서 보려는 것은 조건의 구조다.
        JPQLSerializer serializer = new JPQLSerializer(JPQLTemplates.DEFAULT);
        serializer.handle(predicate);
        return serializer.toString();
    }

    private HouseFilter open() {
        return HouseFilter.builder()
                .statuses(Set.of(NoticeStatus.UPCOMING, NoticeStatus.OPEN))
                .includeUnknown(false)
                .build();
    }
}
