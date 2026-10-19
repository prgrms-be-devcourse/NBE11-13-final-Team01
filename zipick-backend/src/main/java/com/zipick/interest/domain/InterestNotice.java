package com.zipick.interest.domain;

import com.zipick.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 관심 공고. 사용자가 저장한 공고(notice)이며, 일정 캘린더와 마감 알림의 대상이다. */
@Entity
@Getter
@Table(name = "interest_notices",
        uniqueConstraints = @UniqueConstraint(name = "uk_interest_user_notice", columnNames = {"user_id", "notice_id"}),
        indexes = @Index(name = "idx_interest_user", columnList = "user_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterestNotice extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "notice_id", nullable = false)
    private Long noticeId;

    @Column(length = 200)
    private String memo;

    @Builder
    public InterestNotice(Long userId, Long noticeId, String memo) {
        this.userId = userId;
        this.noticeId = noticeId;
        this.memo = memo;
    }

    public void updateMemo(String memo) {
        this.memo = memo;
    }
}
