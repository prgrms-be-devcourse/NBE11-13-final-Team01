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

/** "두 집 중 어디가 더 좋아요?" 답 하나. 쌍대 선택 학습의 입력이다. */
@Entity
@Getter
@Table(name = "recommendation_pair_choices",
        indexes = @Index(name = "idx_rec_pair_user", columnList = "userId"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PairwiseChoice extends BaseTimeEntity {

    public enum Choice { LEFT, RIGHT, TIE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long leftHouseId;

    @Column(nullable = false)
    private Long rightHouseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Choice choice;

    @Builder
    public PairwiseChoice(Long userId, Long leftHouseId, Long rightHouseId, Choice choice) {
        this.userId = userId;
        this.leftHouseId = leftHouseId;
        this.rightHouseId = rightHouseId;
        this.choice = choice;
    }

    /** 학습 목표값: 왼쪽 1, 오른쪽 0, 비슷해요 0.5 */
    public double target() {
        return switch (choice) {
            case LEFT -> 1.0;
            case RIGHT -> 0.0;
            case TIE -> 0.5;
        };
    }
}
