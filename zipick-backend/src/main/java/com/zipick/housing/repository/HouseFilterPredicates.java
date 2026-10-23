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
 * HouseFilter 를 QueryDSL 조건으로 옮기는 곳. 추천 후보 조회와 지도 핀 조회가 같은 판정식을 쓴다.
 *
 * <p>조회 단위가 달라 조건을 두 묶음으로 나눠 둔다.
 * <ul>
 *   <li>{@link #noticeScope} — 공고·주택에서 바로 판정하는 조건
 *   <li>{@link #supplyScope} — 공급 호실(housing_supply) 값으로 판정하는 조건
 * </ul>
 *
 * <p>추천·전체 목록은 호실 단위로 평평하게 조인해 {@link #all} 로 한 번에 건다.
 * 지도는 주택 단위로 읽으면서 공급 조건만 EXISTS 서브쿼리 안으로 넣는다.
 * 그래야 "같은 호실 하나가 선택한 조건을 모두 만족"하는 판정이 된다.
 *
 * <p><b>필터가 실제로 참조하는 위치</b> (이름만으로 짐작하면 안 되는 것들)
 * <ul>
 *   <li>공공임대 유형(rentalTypes) → <b>notice</b>.noticeType / notice.title 로 분류한다
 *   <li>주택 유형(housingTypes) → <b>housing_supply</b>.housingType 원본 문자열이다
 *   <li>지역(regions) → housing.provinceName / districtName
 *   <li>보증금·월세·면적 → housing_supply (금액은 원 단위라 만 원을 곱한다)
 * </ul>
 *
 * <p>active 조건은 넣지 않는다. 조회 단위마다 거는 위치가 다르기 때문이다
 * ({@link #all} 은 두 플래그를 함께, 지도는 housing.active 를 바깥에 / supply.active 를 EXISTS 안에).
 */
public final class HouseFilterPredicates {

    private static final long MANWON = 10_000L;

    private HouseFilterPredicates() {
    }

    /**
     * 호실 단위로 평평하게 조인한 쿼리에 거는 조건 전체.
     * 추천 후보 조회와 전체 공급주택 목록이 쓰던 기존 판정식과 같다.
     */
    public static BooleanBuilder all(HouseFilter f) {
        BooleanBuilder b = new BooleanBuilder();
        b.and(housing.active.isTrue()).and(housingSupply.active.isTrue());
        b.and(noticeScope(f));
        b.and(supplyScope(f));
        return b;
    }

    /** 공고·주택에서 바로 판정하는 조건. 조건이 하나도 없으면 빈 BooleanBuilder 를 돌려준다. */
    public static BooleanBuilder noticeScope(HouseFilter f) {
        BooleanBuilder b = new BooleanBuilder();
        if (notEmpty(f.noticeIds())) b.and(notice.id.in(f.noticeIds()));
        if (notEmpty(f.statuses())) b.and(notice.status.in(f.statuses()));
        if (notEmpty(f.agencies())) b.and(agency.code.in(f.agencies()));
        if (notEmpty(f.rentalTypes())) b.and(rentalTypeIn(f.rentalTypes()));
        if (notEmpty(f.regions())) b.and(regionIn(f.regions()));
        return b;
    }

    /**
     * 공급 호실 값으로 판정하는 조건. 조건이 하나도 없으면 빈 BooleanBuilder 를 돌려준다.
     * 호출부는 {@code hasValue()} 로 "공급 조건을 하나라도 골랐는지"를 판단할 수 있다.
     */
    public static BooleanBuilder supplyScope(HouseFilter f) {
        BooleanBuilder b = new BooleanBuilder();
        boolean unknown = f.includeUnknown();
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

    /**
     * 공공임대 유형 필터. RentalType.classify 와 같은 규칙이다:
     * 공고 유형이 있으면 공고 유형으로, 비어 있으면 공고 제목으로 판단한다.
     */
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

    /** "시도 시군구" 키 → (시도, 시군구) 쌍 비교. 시도 이름에 공백이 없으므로 첫 공백으로 나눈다 */
    private static Predicate regionIn(Set<String> keys) {
        BooleanBuilder or = new BooleanBuilder();
        for (String key : keys) {
            int i = key.indexOf(' ');
            if (i < 0) or.or(housing.provinceName.eq(key).or(housing.districtName.eq(key)));
            else or.or(housing.provinceName.eq(key.substring(0, i)).and(housing.districtName.eq(key.substring(i + 1))));
        }
        return or;
    }

    /** includeUnknown이면 "조건 OR 값 없음", 아니면 조건만 (= 값이 없으면 조건 불충족) */
    private static BooleanExpression orNull(BooleanExpression cond, ComparableExpressionBase<?> col, boolean includeUnknown) {
        return includeUnknown ? cond.or(col.isNull()) : cond;
    }

    private static boolean notEmpty(Set<?> s) {
        return s != null && !s.isEmpty();
    }
}
