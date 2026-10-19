package com.zipick.infrastructure.domain;

import com.zipick.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 주택 한 채의 주변 인프라 수집 스냅샷. Housing 당 1행이다.
 *
 * 이 행이 있다는 것은 "policySignature 에 적힌 카테고리를 모두 정상 조회했다"는 뜻이다.
 * 그래서 Place 행이 없는 카테고리는 '수집 실패'가 아니라 '반경 내 0건'으로 읽으면 된다.
 *
 * Housing 과 JPA 연관을 맺지 않고 housingId 만 들고 있다.
 * 공고 수집 담당 엔티티를 건드리지 않기 위해서이고, 덕분에 지연 로딩 문제도 없다.
 * (FK 는 DDL 로 건다.)
 */
@Entity
@Getter
@Table(
        name = "housing_infra_snapshot",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_housing_infra_snapshot_housing",
                columnNames = "housing_id"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HousingInfraSnapshot extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "housing_infra_snapshot_id")
    private Long id;

    @Column(name = "housing_id", nullable = false)
    private Long housingId;

    /** 수집 당시 정책. 현재 정책과 다르면 재수집 대상이 된다. */
    @Column(name = "policy_signature", nullable = false, length = 500)
    private String policySignature;

    @Column(name = "collected_latitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal collectedLatitude;

    @Column(name = "collected_longitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal collectedLongitude;

    /**
     * 카카오 카테고리 코드별 최근접 시설 요약.
     * {"SW8":{"name":"신설동역","distanceM":683}}
     * 추천(ScoreCalculator)이 기대하는 형식과 같게 두어, 통합 시 조회 대상만 바꾸면 되게 한다.
     * PostgreSQL 에서는 jsonb 로 저장한다. (@Lob 을 쓰면 large object(oid) 가 되어 다루기 어렵다)
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "nearest", columnDefinition = "jsonb")
    private String nearest;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    public HousingInfraSnapshot(
            Long housingId,
            String policySignature,
            BigDecimal collectedLatitude,
            BigDecimal collectedLongitude,
            String nearest,
            Instant collectedAt
    ) {
        this.housingId = housingId;
        this.policySignature = policySignature;
        this.collectedLatitude = collectedLatitude;
        this.collectedLongitude = collectedLongitude;
        this.nearest = nearest;
        this.collectedAt = collectedAt;
    }

    /** 재수집에 성공했을 때만 호출한다. 실패 시에는 기존 값을 그대로 둔다. */
    public void refresh(
            String policySignature,
            BigDecimal collectedLatitude,
            BigDecimal collectedLongitude,
            String nearest,
            Instant collectedAt
    ) {
        this.policySignature = policySignature;
        this.collectedLatitude = collectedLatitude;
        this.collectedLongitude = collectedLongitude;
        this.nearest = nearest;
        this.collectedAt = collectedAt;
    }

    /**
     * 아직 유효한 캐시인지 판단한다. 수집 대상 선정 쿼리와 같은 기준이다.
     *
     * @param ttlThreshold 이 시각보다 오래된 수집 결과는 만료로 본다
     */
    public boolean isValid(
            String currentSignature,
            BigDecimal currentLatitude,
            BigDecimal currentLongitude,
            Instant ttlThreshold
    ) {
        return collectedAt.isAfter(ttlThreshold)
                && policySignature.equals(currentSignature)
                && sameCoordinate(collectedLatitude, currentLatitude)
                && sameCoordinate(collectedLongitude, currentLongitude);
    }

    /** DECIMAL 비교라 equals 가 아니라 compareTo 를 써야 한다 (10.50 과 10.5 는 equals 가 false). */
    private static boolean sameCoordinate(BigDecimal stored, BigDecimal current) {
        return stored != null && current != null && stored.compareTo(current) == 0;
    }
}
