package com.zipick.user.domain;

import com.zipick.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 희망 주거조건. 금액은 만 원, 면적은 ㎡.
 * regions / housingTypes / rentalTypes 는 JSON 문자열.
 *   regions: [{"code":"11440","name":"마포구","rank":1}, ...]  (최대 3)
 */
@Entity
@Getter
@Table(name = "user_preferences")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPreference extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long userId;

    @Lob
    @Column(nullable = false)
    private String regions;

    private Integer depositMin;
    private Integer depositMax;
    private Integer rentMin;
    private Integer rentMax;

    @Column(precision = 6, scale = 2)
    private BigDecimal areaMin;

    @Column(precision = 6, scale = 2)
    private BigDecimal areaMax;

    @Lob
    private String housingTypes;

    @Lob
    private String rentalTypes;

    /** 월 환산 비용 예산(만 원). 비우면 보증금·월세 범위로 계산 */
    private Integer monthlyBudget;

    @Builder
    public UserPreference(Long userId, String regions, Integer depositMin, Integer depositMax, Integer rentMin,
                          Integer rentMax, BigDecimal areaMin, BigDecimal areaMax, String housingTypes,
                          String rentalTypes, Integer monthlyBudget) {
        this.userId = userId;
        update(regions, depositMin, depositMax, rentMin, rentMax, areaMin, areaMax, housingTypes, rentalTypes,
                monthlyBudget);
    }

    public void update(String regions, Integer depositMin, Integer depositMax, Integer rentMin, Integer rentMax,
                       BigDecimal areaMin, BigDecimal areaMax, String housingTypes, String rentalTypes,
                       Integer monthlyBudget) {
        this.regions = regions;
        this.depositMin = depositMin;
        this.depositMax = depositMax;
        this.rentMin = rentMin;
        this.rentMax = rentMax;
        this.areaMin = areaMin;
        this.areaMax = areaMax;
        this.housingTypes = housingTypes;
        this.rentalTypes = rentalTypes;
        this.monthlyBudget = monthlyBudget;
    }
}
