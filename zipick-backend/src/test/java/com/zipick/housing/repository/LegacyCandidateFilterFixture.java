package com.zipick.housing.repository;

import static com.zipick.notice.domain.QAgency.agency;
import static com.zipick.notice.domain.QHousing.housing;
import static com.zipick.notice.domain.QHousingSupply.housingSupply;
import static com.zipick.notice.domain.QNotice.notice;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
import com.querydsl.core.types.dsl.StringExpression;
import com.zipick.housing.domain.RentalType;
import com.zipick.housing.dto.HouseFilter;
import java.util.List;
import java.util.Set;

/**
 * M1(공통 필터 판정식 분리) **이전** CandidateQueryRepository.build() 구현을 그대로 복사해 얼려 둔 것.
 *
 * <p>지도 조회가 같은 판정식을 재사용하려고 조건 구성을 HouseFilterPredicates 로 옮겼다.
 * 옮기는 과정에서 추천 후보 조회 조건이 조금이라도 달라지면 추천 결과가 통째로 바뀌므로,
 * 이 픽스처와 새 구현이 만들어 내는 Predicate 가 같은지를 테스트로 고정한다.
 *
 * <p><b>이 파일은 수정하지 않는다.</b> 필터 정책을 바꿔야 한다면 새 구현만 고치고,
 * 그때는 이 픽스처와의 차이가 의도한 변경인지 테스트 실패로 드러나게 둔다.
 */
final class LegacyCandidateFilterFixture {

    private static final long MANWON = 10_000L;

    private LegacyCandidateFilterFixture() {
    }

    static BooleanBuilder build(HouseFilter f) {
        BooleanBuilder b = new BooleanBuilder();
        boolean unknown = f.includeUnknown();

        b.and(housing.active.isTrue()).and(housingSupply.active.isTrue());
        if (notEmpty(f.noticeIds())) b.and(notice.id.in(f.noticeIds()));
        if (notEmpty(f.statuses())) b.and(notice.status.in(f.statuses()));
        if (notEmpty(f.agencies())) b.and(agency.code.in(f.agencies()));
        if (notEmpty(f.rentalTypes())) b.and(rentalTypeIn(f.rentalTypes()));
        if (notEmpty(f.regions())) b.and(regionIn(f.regions()));
        if (notEmpty(f.housingTypes())) {
            List<String> raws = f.housingTypes().stream().flatMap(h -> h.getRawNames().stream()).toList();
            b.and(orNull(housingSupply.housingType.in(raws), housingSupply.housingType, unknown));
        }
        if (f.depositMin() != null) b.and(orNull(housingSupply.rentDeposit.goe(f.depositMin() * MANWON), housingSupply.rentDeposit, unknown));
        if (f.depositMax() != null) b.and(orNull(housingSupply.rentDeposit.loe(f.depositMax() * MANWON), housingSupply.rentDeposit, unknown));
        if (f.rentMin() != null) b.and(orNull(housingSupply.monthlyRent.goe(f.rentMin() * MANWON), housingSupply.monthlyRent, unknown));
        if (f.rentMax() != null) b.and(orNull(housingSupply.monthlyRent.loe(f.rentMax() * MANWON), housingSupply.monthlyRent, unknown));
        if (f.areaMin() != null) b.and(orNull(housingSupply.exclusiveArea.goe(f.areaMin()), housingSupply.exclusiveArea, unknown));
        if (f.areaMax() != null) b.and(orNull(housingSupply.exclusiveArea.loe(f.areaMax()), housingSupply.exclusiveArea, unknown));
        return b;
    }

    private static Predicate rentalTypeIn(Set<RentalType> types) {
        BooleanExpression typeBlank = notice.noticeType.isNull().or(notice.noticeType.trim().isEmpty());
        BooleanBuilder or = new BooleanBuilder();
        for (RentalType r : types) {
            or.or(typeBlank.not().and(matches(r, notice.noticeType)));
            or.or(typeBlank.and(matches(r, notice.title)));
        }
        return or;
    }

    private static BooleanExpression matches(RentalType r, StringExpression t) {
        BooleanExpression youthOnly = t.contains("청년").and(t.contains("신혼").not());
        return switch (r) {
            case HAPPY -> t.contains("행복주택");
            case NATIONAL -> t.contains("국민임대");
            case PERMANENT -> t.contains("영구임대");
            case INTEGRATED -> t.contains("통합공공임대");
            case DEUNDEUN -> t.contains("든든전세");
            case JEONSE_LEASE -> t.contains("전세임대");
            case YOUTH_PURCHASE -> t.contains("매입").and(youthOnly);
            case PURCHASE -> t.contains("매입").and(youthOnly.not());
            case PUBLIC -> t.contains("공공임대").and(t.contains("통합").not())
                    .or(t.contains("10년임대")).or(t.contains("5년임대"));
        };
    }

    private static Predicate regionIn(Set<String> keys) {
        BooleanBuilder or = new BooleanBuilder();
        for (String key : keys) {
            int i = key.indexOf(' ');
            if (i < 0) or.or(housing.provinceName.eq(key).or(housing.districtName.eq(key)));
            else or.or(housing.provinceName.eq(key.substring(0, i)).and(housing.districtName.eq(key.substring(i + 1))));
        }
        return or;
    }

    private static BooleanExpression orNull(BooleanExpression cond, ComparableExpressionBase<?> col, boolean includeUnknown) {
        return includeUnknown ? cond.or(col.isNull()) : cond;
    }

    private static boolean notEmpty(Set<?> s) {
        return s != null && !s.isEmpty();
    }
}
