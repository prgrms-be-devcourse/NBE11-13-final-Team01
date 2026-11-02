package com.zipick.housing.repository;

import static com.zipick.notice.domain.QAgency.agency;
import static com.zipick.notice.domain.QHousing.housing;
import static com.zipick.notice.domain.QHousingSupply.housingSupply;
import static com.zipick.notice.domain.QNotice.notice;
import static com.zipick.recommendation.domain.QRecommendationFeedback.recommendationFeedback;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.zipick.housing.domain.Candidate;
import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import com.zipick.housing.dto.HouseFilter;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 추천 후보 조회. 공고 수집 테이블(notice ⨝ housing ⨝ housing_supply ⟕ agency)을 읽는다.
 *
 * 결과에서 '빼는' 조건(모집 상태, 지역, 금액, 면적, 유형, 숨긴 집)은 모두 여기서 SQL 로 처리한다.
 * 점수·근거는 코드(ScoreCalculator)가 맡는다. — 규칙: 빼는 조건은 DB, 순서와 근거는 코드
 */
@Repository
@RequiredArgsConstructor
public class CandidateQueryRepository {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long MANWON = 10_000L;

    private final JPAQueryFactory query;

    /** 필터를 통과한 공급 호실. hiddenForUserId 가 있으면 그 사용자가 숨긴 주택을 뺀다. */
    public List<Candidate> find(HouseFilter f, Long hiddenForUserId, int limit) {
        BooleanBuilder where = build(f);
        if (hiddenForUserId != null) {
            where.and(JPAExpressions.selectOne().from(recommendationFeedback)
                    .where(recommendationFeedback.userId.eq(hiddenForUserId),
                            recommendationFeedback.housingId.eq(housing.id))
                    .notExists());
        }
        return select()
                .where(where)
                .orderBy(notice.applyEndAt.asc().nullsLast(), housing.id.asc(), housingSupply.id.asc())
                .limit(limit)
                .fetch().stream().map(this::toCandidate).toList();
    }

    /** 주택 id 로 조회 (모집 상태·활성 여부와 관계없이). 상세·두 집 비교·학습에 쓴다. */
    public List<Candidate> findByHousingIds(Collection<Long> housingIds) {
        if (housingIds.isEmpty()) return List.of();
        return select()
                .where(housing.id.in(housingIds))
                .orderBy(housing.id.asc(), housingSupply.id.asc())
                .fetch().stream().map(this::toCandidate).toList();
    }

    /** 필터에 맞는 주택 수 */
    public long countHousings(HouseFilter f) {
        Long n = query.select(housing.id.countDistinct())
                .from(housingSupply)
                .join(housingSupply.housing, housing)
                .join(housing.notice, notice)
                .leftJoin(notice.agency, agency)
                .where(build(f))
                .fetchOne();
        return n == null ? 0 : n;
    }

    public enum Dimension { STATUS, AGENCY, RENTAL_TYPE, HOUSING_TYPE, REGION }

    /**
     * 필터에 맞는 주택 수를 값별로 센다 (필터 칩 개수 표시용).
     * 공고 유형·주택 유형은 원본 문자열로 센 뒤 분류해서 합친다. 분류할 수 없는 값은 세지 않는다.
     */
    public Map<String, Long> countBy(HouseFilter f, Dimension d) {
        Map<String, Long> out = new LinkedHashMap<>();
        switch (d) {
            case STATUS -> group(f, notice.status).forEach(t -> add(out, String.valueOf(t.get(0, Object.class)), t));
            case AGENCY -> group(f, agency.code).forEach(t -> add(out, t.get(0, String.class), t));
            case RENTAL_TYPE -> query.select(notice.noticeType, notice.title, housing.id.countDistinct())
                    .from(housingSupply).join(housingSupply.housing, housing).join(housing.notice, notice)
                    .leftJoin(notice.agency, agency)
                    .where(build(f))
                    .groupBy(notice.noticeType, notice.title)
                    .fetch()
                    .forEach(t -> {
                        RentalType r = RentalType.classify(t.get(notice.noticeType), t.get(notice.title));
                        if (r != null) out.merge(r.name(), t.get(housing.id.countDistinct()), Long::sum);
                    });
            case HOUSING_TYPE -> group(f, housingSupply.housingType).forEach(t -> {
                HousingType h = HousingType.classify(t.get(0, String.class));
                if (h != null) add(out, h.name(), t);
            });
            case REGION -> query.select(housing.provinceName, housing.districtName, housing.id.countDistinct())
                    .from(housingSupply).join(housingSupply.housing, housing).join(housing.notice, notice)
                    .leftJoin(notice.agency, agency)
                    .where(build(f), housing.districtName.isNotNull())
                    .groupBy(housing.provinceName, housing.districtName)
                    .orderBy(housing.provinceName.asc(), housing.districtName.asc())
                    .fetch()
                    .forEach(t -> out.put(Candidate.regionKey(t.get(housing.provinceName), t.get(housing.districtName)),
                            t.get(housing.id.countDistinct())));
        }
        return out;
    }

    /** 기관 코드 → 기관 이름 */
    public Map<String, String> agencyNames() {
        Map<String, String> out = new LinkedHashMap<>();
        query.select(agency.code, agency.name).from(agency).orderBy(agency.code.asc()).fetch()
                .forEach(t -> out.put(t.get(agency.code), t.get(agency.name)));
        return out;
    }

    // ---- 내부 ----

    private JPAQuery<Tuple> select() {
        return query.select(housingSupply.id, housing.id, notice.id, notice.title, notice.noticeType, notice.status,
                        notice.applyEndAt, notice.detailUrl, agency.code, agency.name,
                        housing.name, housing.address, housing.provinceName, housing.districtName,
                        housing.latitude, housing.longitude, housing.buildYear,
                        housingSupply.housingType, housingSupply.exclusiveArea, housingSupply.roomCount,
                        housingSupply.floor, housingSupply.rentDeposit, housingSupply.monthlyRent,
                        housingSupply.supplyCount)
                .from(housingSupply)
                .join(housingSupply.housing, housing)
                .join(housing.notice, notice)
                .leftJoin(notice.agency, agency);
    }

    private Candidate toCandidate(Tuple t) {
        Instant end = t.get(notice.applyEndAt);
        return new Candidate(
                t.get(housingSupply.id), t.get(housing.id), t.get(notice.id),
                t.get(notice.title), t.get(notice.noticeType), RentalType.classify(t.get(notice.noticeType), t.get(notice.title)),
                t.get(notice.status), end == null ? null : LocalDate.ofInstant(end, SEOUL), t.get(notice.detailUrl),
                t.get(agency.code), t.get(agency.name),
                t.get(housing.name), t.get(housing.address), t.get(housing.provinceName), t.get(housing.districtName),
                t.get(housing.latitude), t.get(housing.longitude), t.get(housing.buildYear),
                t.get(housingSupply.housingType), HousingType.classify(t.get(housingSupply.housingType)),
                t.get(housingSupply.exclusiveArea), t.get(housingSupply.roomCount), t.get(housingSupply.floor),
                Candidate.toManwon(t.get(housingSupply.rentDeposit)), Candidate.toManwon(t.get(housingSupply.monthlyRent)),
                t.get(housingSupply.supplyCount));
    }

    private List<Tuple> group(HouseFilter f, ComparableExpressionBase<?> key) {
        return query.select(key, housing.id.countDistinct())
                .from(housingSupply).join(housingSupply.housing, housing).join(housing.notice, notice)
                .leftJoin(notice.agency, agency)
                .where(build(f), key.isNotNull())
                .groupBy(key)
                .fetch();
    }

    private static void add(Map<String, Long> out, String key, Tuple t) {
        out.merge(key, t.get(1, Long.class), Long::sum);
    }

    /**
     * 필터 조건은 HouseFilterPredicates 로 옮겼다. 지도 핀 조회(주택 단위)가 같은 판정식을 써야 해서다.
     * 여기서 쓰는 조건 자체는 바뀌지 않았다 (HouseFilterPredicatesEquivalenceTest 로 고정).
     */
    private BooleanBuilder build(HouseFilter f) {
        return HouseFilterPredicates.all(f);
    }
}
