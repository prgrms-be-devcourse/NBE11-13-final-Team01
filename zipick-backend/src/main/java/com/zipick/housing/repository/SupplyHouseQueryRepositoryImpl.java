package com.zipick.housing.repository;

import static com.zipick.housing.domain.QAnnouncement.announcement;
import static com.zipick.housing.domain.QSupplyHouse.supplyHouse;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.EnumPath;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.zipick.housing.domain.SupplyHouse;
import com.zipick.housing.dto.HouseFilter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class SupplyHouseQueryRepositoryImpl implements SupplyHouseQueryRepository {

    private final JPAQueryFactory query;

    @Override
    public List<SupplyHouse> findByFilter(HouseFilter f, int limit) {
        return query.selectFrom(supplyHouse)
                .join(supplyHouse.announcement, announcement).fetchJoin()
                .where(build(f))
                .orderBy(announcement.closeDate.asc().nullsLast(), supplyHouse.id.asc())
                .limit(limit)
                .fetch();
    }

    @Override
    public long countByFilter(HouseFilter f) {
        Long n = query.select(supplyHouse.count())
                .from(supplyHouse)
                .join(supplyHouse.announcement, announcement)
                .where(build(f))
                .fetchOne();
        return n == null ? 0 : n;
    }

    @Override
    public Map<String, Long> countBy(HouseFilter f, Dimension dimension) {
        EnumPath<? extends Enum<?>> key = switch (dimension) {
            case STATUS -> announcement.status;
            case AGENCY -> announcement.agency;
            case RENTAL_TYPE -> announcement.rentalType;
            case HOUSING_TYPE -> supplyHouse.housingType;
        };
        Map<String, Long> out = new LinkedHashMap<>();
        for (Tuple t : query.select(key, supplyHouse.count())
                .from(supplyHouse)
                .join(supplyHouse.announcement, announcement)
                .where(build(f), key.isNotNull())
                .groupBy(key)
                .fetch()) {
            out.put(t.get(key).name(), t.get(supplyHouse.count()));
        }
        return out;
    }

    private BooleanBuilder build(HouseFilter f) {
        BooleanBuilder b = new BooleanBuilder();
        boolean unknown = f.includeUnknown();

        if (notEmpty(f.statuses())) b.and(announcement.status.in(f.statuses()));
        if (notEmpty(f.agencies())) b.and(announcement.agency.in(f.agencies()));
        if (notEmpty(f.rentalTypes())) b.and(announcement.rentalType.in(f.rentalTypes()));
        if (notEmpty(f.sigunguCodes())) b.and(orNull(supplyHouse.sigunguCode.in(f.sigunguCodes()), supplyHouse.sigunguCode, unknown));
        if (notEmpty(f.housingTypes())) b.and(orNull(supplyHouse.housingType.in(f.housingTypes()), supplyHouse.housingType, unknown));

        if (f.depositMin() != null) b.and(orNull(supplyHouse.deposit.goe(f.depositMin()), supplyHouse.deposit, unknown));
        if (f.depositMax() != null) b.and(orNull(supplyHouse.deposit.loe(f.depositMax()), supplyHouse.deposit, unknown));
        if (f.rentMin() != null) b.and(orNull(supplyHouse.monthlyRent.goe(f.rentMin()), supplyHouse.monthlyRent, unknown));
        if (f.rentMax() != null) b.and(orNull(supplyHouse.monthlyRent.loe(f.rentMax()), supplyHouse.monthlyRent, unknown));
        if (f.areaMin() != null) b.and(orNull(supplyHouse.exclusiveArea.goe(f.areaMin()), supplyHouse.exclusiveArea, unknown));
        if (f.areaMax() != null) b.and(orNull(supplyHouse.exclusiveArea.loe(f.areaMax()), supplyHouse.exclusiveArea, unknown));
        return b;
    }

    /** includeUnknown이면 "조건 OR 값 없음", 아니면 조건만 */
    private BooleanExpression orNull(BooleanExpression cond, ComparableExpressionBase<?> col, boolean includeUnknown) {
        return includeUnknown ? cond.or(col.isNull()) : cond;
    }

    private boolean notEmpty(Set<?> s) {
        return s != null && !s.isEmpty();
    }
}
