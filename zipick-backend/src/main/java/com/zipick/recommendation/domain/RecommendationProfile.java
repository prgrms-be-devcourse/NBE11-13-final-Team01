package com.zipick.recommendation.domain;

import com.zipick.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자의 추천 기준. 사용자당 최대 3개, 그중 1개만 활성.
 *
 * weights:      {"cost":5,"region":4,"area":3,"infra":3,"type":2}   항목별 우선순위 1~5
 * rules:        {"region":"MUST","cost":"PREFER"}                     MUST면 점수 0인 집 제외
 * infraWeights: {"SW8":5,"CS2":4,"MT1":2}                             카카오 카테고리별 가중치 1~5
 */
@Entity
@Getter
@Table(name = "recommendation_profiles", indexes = @Index(name = "idx_rec_profile_user", columnList = "userId"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecommendationProfile extends BaseTimeEntity {

    public static final int MAX_PER_USER = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 30)
    private String name;

    @Column(nullable = false)
    private boolean active;

    @Lob
    @Column(nullable = false)
    private String weights;

    @Lob
    private String rules;

    @Lob
    private String infraWeights;

    @Column(nullable = false)
    private int version;

    @Builder
    public RecommendationProfile(Long userId, String name, boolean active, String weights, String rules,
                                 String infraWeights) {
        this.userId = userId;
        this.name = name;
        this.active = active;
        this.weights = weights;
        this.rules = rules;
        this.infraWeights = infraWeights;
        this.version = 1;
    }

    public void update(String name, String weights, String rules, String infraWeights) {
        if (name != null) this.name = name;
        if (weights != null) this.weights = weights;
        if (rules != null) this.rules = rules;
        if (infraWeights != null) this.infraWeights = infraWeights;
        bumpVersion();
    }

    public void activate() { this.active = true; }
    public void deactivate() { this.active = false; }
    public void bumpVersion() { this.version++; }
}
