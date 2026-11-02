package com.zipick.notice.repository;

import static com.zipick.notice.domain.QAgency.agency;
import static com.zipick.notice.domain.QHousing.housing;
import static com.zipick.notice.domain.QHousingSupply.housingSupply;
import static com.zipick.notice.domain.QNotice.notice;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Predicate;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.zipick.housing.dto.HouseFilter;
import com.zipick.housing.repository.HouseFilterPredicates;
import com.zipick.notice.domain.Housing;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 지도 핀 조회. **주택(Housing) 단위**로 읽어 주택 하나당 결과 한 건을 돌려준다.
 *
 * <p>전체 공급주택 목록(CandidateQueryRepository)과 조회 단위가 다르다.
 * 그쪽은 호실(housing_supply)을 평평하게 조인해 읽은 뒤 주택으로 묶으므로
 * 호실 수에 따라 내부 조회 상한에 걸릴 수 있지만, 여기는 주택 행만 읽어 그 제약이 없다.
 *
 * <p>필터 판정식은 {@link HouseFilterPredicates} 를 공유한다. 조건을 두 군데에 거는 것이 핵심이다.
 * <ul>
 *   <li>공고·주택 조건 → 바깥 WHERE
 *   <li>공급 조건 → 활성 호실에 대한 EXISTS 서브쿼리 안에서 AND
 * </ul>
 * 그래서 "서로 다른 호실이 조건을 하나씩 만족"하는 경우가 아니라
 * <b>같은 호실 하나가 고른 조건을 모두 만족</b>해야 주택이 포함된다.
 *
 * <p>notice 패키지가 housing 패키지를 참조하는 유일한 지점이다.
 * 필터 계약(HouseFilter)과 판정식을 전체 목록·추천과 공유하기 위해 감수한 의존이다.
 */
@Repository
@RequiredArgsConstructor
public class HousingPinQueryRepository {

    private final JPAQueryFactory query;

    /**
     * 지도에 찍을 주택. 호출부는 공개 지도 정책을 적용한 필터를 넘겨야 한다
     * (MapHousingFilterPolicy: 모집 상태 교집합 + 공급 조건 엄격 판정).
     *
     * <p>좌표가 없는 주택은 핀을 만들 수 없어 제외한다. 활성 공급 정보가 없는 주택은
     * 공급 조건을 고르지 않았다면 그대로 포함한다 — 공고가 유효하면 보여 주는 것이 정책이다.
     */
    public List<Housing> findPins(HouseFilter filter) {
        // 허용 상태와의 교집합이 비면 보여 줄 것이 없다.
        // 여기서 조건을 빼 버리면 '상태 필터 없음'이 되어 전체 주택이 나오므로 쿼리 자체를 하지 않는다.
        if (filter.statuses() == null || filter.statuses().isEmpty()) {
            return List.of();
        }

        return query.selectFrom(housing)
                .join(housing.notice, notice).fetchJoin()
                .leftJoin(notice.agency, agency).fetchJoin()
                .where(pinWhere(filter))
                .orderBy(housing.id.asc())
                .fetch();
    }

    /**
     * 핀 조회의 WHERE 절. 조건 구성을 따로 떼어 DB 없이도 검증할 수 있게 한다.
     * 공급 조건은 이 안에서 EXISTS 서브쿼리로만 등장해야 한다 — 바깥에 housing_supply 가 새어 나오면
     * 조인이 생겨 주택이 호실 수만큼 중복되고, 서로 다른 호실의 조건이 섞인다.
     */
    BooleanBuilder pinWhere(HouseFilter filter) {
        BooleanBuilder where = new BooleanBuilder();
        where.and(housing.active.isTrue());
        where.and(housing.latitude.isNotNull());
        where.and(housing.longitude.isNotNull());
        where.and(HouseFilterPredicates.noticeScope(filter));
        Predicate supplyExists = activeSupplyExists(filter);
        if (supplyExists != null) {
            where.and(supplyExists);
        }
        return where;
    }

    /**
     * 공급 조건을 하나라도 고른 경우에만 EXISTS 를 건다.
     * 고르지 않았으면 null 을 돌려줘 조건이 붙지 않는다(= 공급 정보가 없어도 조회된다).
     *
     * <p>active=false 인 호실은 현재 유효한 공급 조건으로 보지 않으므로 EXISTS 대상에서 뺀다.
     */
    Predicate activeSupplyExists(HouseFilter filter) {
        BooleanBuilder supplyConditions = HouseFilterPredicates.supplyScope(filter);
        if (!supplyConditions.hasValue()) {
            return null;
        }
        return JPAExpressions.selectOne()
                .from(housingSupply)
                .where(
                        housingSupply.housing.eq(housing),
                        housingSupply.active.isTrue(),
                        supplyConditions
                )
                .exists();
    }
}
