package com.zipick.user.domain;

import com.zipick.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.Period;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자 기본 정보 (지원조건 판정용). 금액은 만 원.
 * TODO(보안): monthlyIncome/totalAssets/carValue 는 운영 전 AES 암호화 컨버터 적용.
 */
@Entity
@Getter
@Table(name = "user_profiles")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserProfile extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long userId;

    @Column(nullable = false)
    private LocalDate birthDate;

    @Column(length = 30)
    private String job;

    @Column(nullable = false)
    private boolean married;

    @Column(nullable = false)
    private int householdSize;

    private Integer monthlyIncome;
    private Integer totalAssets;
    private Integer carValue;

    @Column(nullable = false)
    private boolean homeless;

    @Builder
    public UserProfile(Long userId, LocalDate birthDate, String job, boolean married, int householdSize,
                       Integer monthlyIncome, Integer totalAssets, Integer carValue, boolean homeless) {
        this.userId = userId;
        update(birthDate, job, married, householdSize, monthlyIncome, totalAssets, carValue, homeless);
    }

    public void update(LocalDate birthDate, String job, boolean married, int householdSize,
                       Integer monthlyIncome, Integer totalAssets, Integer carValue, boolean homeless) {
        this.birthDate = birthDate;
        this.job = job;
        this.married = married;
        this.householdSize = householdSize;
        this.monthlyIncome = monthlyIncome;
        this.totalAssets = totalAssets;
        this.carValue = carValue;
        this.homeless = homeless;
    }

    public int age(LocalDate today) {
        return Period.between(birthDate, today).getYears();
    }
}
