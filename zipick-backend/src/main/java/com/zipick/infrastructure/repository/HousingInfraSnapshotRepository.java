package com.zipick.infrastructure.repository;

import com.zipick.infrastructure.domain.HousingInfraSnapshot;
import com.zipick.notice.domain.Housing;
import com.zipick.notice.domain.NoticeStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface HousingInfraSnapshotRepository extends JpaRepository<HousingInfraSnapshot, Long> {

    Optional<HousingInfraSnapshot> findByHousingId(Long housingId);

    /**
     * 추천 연동용 배치 조회. 후보 주택들의 nearest 요약을 한 번에 읽는다.
     * (이번 단계에서는 추천과 연결하지 않고, 통합 시점에 바로 쓸 수 있도록 두기만 한다.)
     */
    List<HousingInfraSnapshot> findAllByHousingIdIn(Collection<Long> housingIds);

    /**
     * 수집·재수집이 필요한 주택을 찾는다. 네 가지 트리거를 쿼리 하나로 처리한다.
     *   1. 스냅샷이 없는 신규 주택
     *   2. TTL 이 지난 주택
     *   3. 수집 정책(카테고리·반경)이 바뀐 주택
     *   4. 좌표가 바뀐 주택
     *
     * 대상 공고 상태는 추천 후보와 같은 집합을 쓴다(HouseFilter.OPEN_STATUSES = 공고 예정 + 접수 중).
     * 범위가 다르면 추천 목록 안에서 어떤 집만 인프라 점수를 받아 순위 비교가 공정하지 않다.
     * 좌표가 아직 없는 주택은 제외하므로, 공고 수집 → 좌표 보완이 끝난 뒤에만 대상이 된다.
     * Housing 과 연관이 없는 엔티티라 on 절로 직접 조인한다.
     */
    @Query("""
            select h
            from Housing h
            left join HousingInfraSnapshot s on s.housingId = h.id
            where h.active = true
              and h.notice.status in :statuses
              and h.latitude is not null
              and h.longitude is not null
              and (
                   s.id is null
                or s.collectedAt < :ttlThreshold
                or s.policySignature <> :policySignature
                or s.collectedLatitude <> h.latitude
                or s.collectedLongitude <> h.longitude
              )
            order by h.id
            """)
    List<Housing> findCollectionTargets(
            @Param("statuses") Collection<NoticeStatus> statuses,
            @Param("ttlThreshold") Instant ttlThreshold,
            @Param("policySignature") String policySignature,
            Pageable pageable
    );
}
