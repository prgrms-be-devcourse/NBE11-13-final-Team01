package com.zipick.user.domain;

import com.zipick.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 희망 주거조건. 금액은 만 원, 면적은 ㎡.
 * regions / housingTypes / rentalTypes 는 JSON 문자열.
 *   regions: [{"code":"경상북도 구미시","name":"구미시","rank":1}, ...]  (최대 3, code = "시도 시군구")
 */
@Entity
@Getter
@Table(name = "user_preferences")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPreference extends BaseTimeEntity {

    /** JSON 문자열 컬럼은 text 로 둔다. @Lob 을 쓰면 PostgreSQL 에서 대용량 객체(oid)가 되어 SQL 로 읽을 수 없다 */
    private static final String JSON_COLUMN = "text";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long userId;

    @Column(nullable = false, columnDefinition = JSON_COLUMN)
    private String regions;

    /** 보증금·월세 상한(만 원). 월 예산이 없을 때 예산을 계산하는 데 쓴다 */
    private Integer depositMax;
    private Integer rentMax;

    @Column(precision = 6, scale = 2)
    private BigDecimal areaMin;

    @Column(precision = 6, scale = 2)
    private BigDecimal areaMax;

    @Column(columnDefinition = JSON_COLUMN)
    private String housingTypes;

    @Column(columnDefinition = JSON_COLUMN)
    private String rentalTypes;

    /** 월 환산 비용 예산(만 원). 비우면 보증금·월세 범위로 계산 */
    private Integer monthlyBudget;

    /** 희망 최소 방 수. 비우면 방 수 점수를 계산하지 않는다 */
    private Integer roomsMin;

    @Builder
    public UserPreference(Long userId, String regions, Integer depositMax, Integer rentMax,
                          BigDecimal areaMin, BigDecimal areaMax, String housingTypes, String rentalTypes,
                          Integer monthlyBudget, Integer roomsMin) {
        this.userId = userId;
        update(regions, depositMax, rentMax, areaMin, areaMax, housingTypes, rentalTypes, monthlyBudget, roomsMin);
    }

    public void update(String regions, Integer depositMax, Integer rentMax,
                       BigDecimal areaMin, BigDecimal areaMax, String housingTypes, String rentalTypes,
                       Integer monthlyBudget, Integer roomsMin) {
        this.regions = regions;
        this.depositMax = depositMax;
        this.rentMax = rentMax;
        this.areaMin = areaMin;
        this.areaMax = areaMax;
        this.housingTypes = housingTypes;
        this.rentalTypes = rentalTypes;
        this.monthlyBudget = monthlyBudget;
        this.roomsMin = roomsMin;
    }
}
