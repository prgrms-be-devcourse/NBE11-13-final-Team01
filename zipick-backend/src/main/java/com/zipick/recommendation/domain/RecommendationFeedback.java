package com.zipick.recommendation.domain;

import com.zipick.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 추천 피드백. 숨김/별로예요 모두 해당 집을 추천에서 뺀다. */
@Entity
@Getter
@Table(name = "recommendation_feedbacks",
        indexes = @Index(name = "idx_rec_feedback_user", columnList = "userId"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecommendationFeedback extends BaseTimeEntity {

    public enum Type { HIDE, DISLIKE }

    /** "별로예요" 이유. 추천 점수 항목과 연결된다. */
    public enum Reason { EXPENSIVE, REGION, SMALL, OLD, INFRA, ETC }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long supplyHouseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Reason reason;

    @Builder
    public RecommendationFeedback(Long userId, Long supplyHouseId, Type type, Reason reason) {
        this.userId = userId;
        this.supplyHouseId = supplyHouseId;
        this.type = type;
        this.reason = reason;
    }
}
