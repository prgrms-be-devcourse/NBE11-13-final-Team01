package com.zipick.housing.domain;

import com.zipick.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공급주택별 주변 인프라 요약. (카카오 Local 수집 결과)
 * categoryCounts / nearest 는 JSON 문자열로 저장한다.
 *   categoryCounts: {"SW8":1,"CS2":4,...}
 *   nearest:        {"SW8":{"name":"망원역","distanceM":420},...}
 */
@Entity
@Getter
@Table(name = "infra_summaries")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InfraSummary extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supply_house_id", unique = true)
    private SupplyHouse supplyHouse;

    @Lob
    @Column(nullable = false)
    private String categoryCounts;

    @Lob
    private String nearest;

    @Column(length = 500)
    private String aiSummary;

    private LocalDateTime collectedAt;

    @Builder
    public InfraSummary(SupplyHouse supplyHouse, String categoryCounts, String nearest, String aiSummary,
                        LocalDateTime collectedAt) {
        this.supplyHouse = supplyHouse;
        this.categoryCounts = categoryCounts;
        this.nearest = nearest;
        this.aiSummary = aiSummary;
        this.collectedAt = collectedAt;
    }
}
