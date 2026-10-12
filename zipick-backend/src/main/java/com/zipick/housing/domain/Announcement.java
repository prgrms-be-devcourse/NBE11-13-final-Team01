package com.zipick.housing.domain;

import com.zipick.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공고. (A 담당 도메인 — 추천·필터에 필요한 최소 컬럼만 정의. 수집 구현 시 확장)
 */
@Entity
@Getter
@Table(name = "announcements")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Announcement extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** LH 공고 ID(PAN_ID) 등 외부 식별자 */
    @Column(nullable = false, unique = true, length = 30)
    private String externalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Agency agency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RentalType rentalType;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 50)
    private String region;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnnouncementStatus status;

    private LocalDate noticeDate;
    private LocalDate closeDate;

    @Column(length = 500)
    private String pdfUrl;

    @Column(length = 500)
    private String detailUrl;

    @Builder
    public Announcement(String externalId, Agency agency, RentalType rentalType, String title, String region,
                        AnnouncementStatus status, LocalDate noticeDate, LocalDate closeDate,
                        String pdfUrl, String detailUrl) {
        this.externalId = externalId;
        this.agency = agency;
        this.rentalType = rentalType;
        this.title = title;
        this.region = region;
        this.status = status;
        this.noticeDate = noticeDate;
        this.closeDate = closeDate;
        this.pdfUrl = pdfUrl;
        this.detailUrl = detailUrl;
    }
}
