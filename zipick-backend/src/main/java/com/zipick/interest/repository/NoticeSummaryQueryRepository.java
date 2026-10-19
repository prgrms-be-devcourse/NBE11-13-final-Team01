package com.zipick.interest.repository;

import static com.zipick.notice.domain.QAgency.agency;
import static com.zipick.notice.domain.QHousing.housing;
import static com.zipick.notice.domain.QNotice.notice;

import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.zipick.housing.domain.Candidate;
import com.zipick.housing.domain.RentalType;
import com.zipick.interest.dto.NoticeSummary;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 공고 요약 조회. 공고 수집 테이블을 읽기만 한다. */
@Repository
@RequiredArgsConstructor
public class NoticeSummaryQueryRepository {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int MAX_REGIONS = 5;

    private final JPAQueryFactory query;

    public Map<Long, NoticeSummary> findByIds(Collection<Long> noticeIds) {
        if (noticeIds.isEmpty()) return Map.of();

        // 공고별 지역 목록
        Map<Long, List<String>> regions = new LinkedHashMap<>();
        Map<Long, Long> counts = new LinkedHashMap<>();
        for (Tuple t : query.select(housing.notice.id, housing.provinceName, housing.districtName, housing.id.count())
                .from(housing)
                .where(housing.notice.id.in(noticeIds), housing.active.isTrue())
                .groupBy(housing.notice.id, housing.provinceName, housing.districtName)
                .orderBy(housing.notice.id.asc(), housing.id.count().desc())
                .fetch()) {
            Long id = t.get(housing.notice.id);
            counts.merge(id, t.get(housing.id.count()), Long::sum);
            List<String> list = regions.computeIfAbsent(id, k -> new ArrayList<>());
            String key = Candidate.regionKey(t.get(housing.provinceName), t.get(housing.districtName));
            if (key != null && list.size() < MAX_REGIONS) list.add(key);
        }

        Map<Long, NoticeSummary> out = new LinkedHashMap<>();
        for (Tuple t : query.select(notice.id, notice.title, notice.noticeType, notice.status, agency.name,
                        notice.detailUrl, notice.announcedAt, notice.applyStartAt, notice.applyEndAt, notice.winnerAnnouncedAt)
                .from(notice).leftJoin(notice.agency, agency)
                .where(notice.id.in(noticeIds))
                .fetch()) {
            Long id = t.get(notice.id);
            out.put(id, new NoticeSummary(id, t.get(notice.title), t.get(notice.noticeType),
                    RentalType.classify(t.get(notice.noticeType), t.get(notice.title)),
                    t.get(notice.status), t.get(agency.name), t.get(notice.detailUrl),
                    t.get(notice.announcedAt), day(t.get(notice.applyStartAt)), day(t.get(notice.applyEndAt)),
                    t.get(notice.winnerAnnouncedAt),
                    counts.getOrDefault(id, 0L), regions.getOrDefault(id, List.of())));
        }
        return out;
    }

    public boolean exists(Long noticeId) {
        return query.selectOne().from(notice).where(notice.id.eq(noticeId)).fetchFirst() != null;
    }

    /** 이 시각 이후 수집된(새로 생긴) 공고 id */
    public List<Long> findCreatedAfter(LocalDateTime since) {
        return query.select(notice.id).from(notice).where(notice.createdAt.gt(since)).orderBy(notice.id.asc()).fetch();
    }

    private static LocalDate day(Instant i) {
        return i == null ? null : LocalDate.ofInstant(i, SEOUL);
    }
}
