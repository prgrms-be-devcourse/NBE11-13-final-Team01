package com.zipick.housing.domain;

import com.zipick.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공급주택. 지도 핀·추천·찜의 단위.
 * 금액은 만 원 단위, 면적은 ㎡.
 */
@Entity
@Getter
@Table(name = "supply_houses", indexes = {
        @Index(name = "idx_supply_house_announcement", columnList = "announcement_id"),
        @Index(name = "idx_supply_house_latlng", columnList = "lat, lng"),
        @Index(name = "idx_supply_house_sigungu", columnList = "sigunguCode")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SupplyHouse extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "announcement_id")
    private Announcement announcement;

    @Column(nullable = false, length = 200)
    private String address;

    /** 시군구 코드 (예: 11440 마포구). 지역 필터·추천에 사용 */
    @Column(length = 10)
    private String sigunguCode;

    @Column(precision = 10, scale = 7)
    private BigDecimal lat;

    @Column(precision = 10, scale = 7)
    private BigDecimal lng;

    /** 동 단위로만 좌표를 구한 경우 true */
    @Column(nullable = false)
    private boolean approximate;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private HousingType housingType;

    private Integer builtYear;
    private Integer floor;

    @Column(precision = 6, scale = 2)
    private BigDecimal exclusiveArea;

    /** 임대보증금 (만 원) */
    private Integer deposit;

    /** 월 임대료 (만 원) */
    private Integer monthlyRent;

    /** 공고문에 적힌 보증금-월세 전환이율 (연 %). 없으면 null */
    @Column(precision = 5, scale = 2)
    private BigDecimal conversionRate;

    @Column(length = 30)
    private String purchaseType;

    private Integer applicants;
    private Integer supplyCount;

    @Builder
    public SupplyHouse(Announcement announcement, String address, String sigunguCode, BigDecimal lat, BigDecimal lng,
                       boolean approximate, HousingType housingType, Integer builtYear, Integer floor,
                       BigDecimal exclusiveArea, Integer deposit, Integer monthlyRent, BigDecimal conversionRate,
                       String purchaseType, Integer applicants, Integer supplyCount) {
        this.announcement = announcement;
        this.address = address;
        this.sigunguCode = sigunguCode;
        this.lat = lat;
        this.lng = lng;
        this.approximate = approximate;
        this.housingType = housingType;
        this.builtYear = builtYear;
        this.floor = floor;
        this.exclusiveArea = exclusiveArea;
        this.deposit = deposit;
        this.monthlyRent = monthlyRent;
        this.conversionRate = conversionRate;
        this.purchaseType = purchaseType;
        this.applicants = applicants;
        this.supplyCount = supplyCount;
    }
}
