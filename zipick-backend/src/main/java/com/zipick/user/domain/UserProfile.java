package com.zipick.user.domain;

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

    @Column(nullable = false)
    private boolean married;

    @Column(nullable = false)
    private int householdSize;

    private Integer monthlyIncome;
    private Integer totalAssets;
    private Integer carValue;

    @Column(nullable = false)
    private boolean homeless;

    /** 대학생·취업준비생 여부. 청년 매입임대는 연령 대신 이 자격으로도 신청할 수 있다 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private StudentStatus studentStatus;

    @Builder
    public UserProfile(Long userId, LocalDate birthDate, boolean married, int householdSize,
                       Integer monthlyIncome, Integer totalAssets, Integer carValue, boolean homeless,
                       StudentStatus studentStatus) {
        this.userId = userId;
        update(birthDate, married, householdSize, monthlyIncome, totalAssets, carValue, homeless, studentStatus);
    }

    public void update(LocalDate birthDate, boolean married, int householdSize,
                       Integer monthlyIncome, Integer totalAssets, Integer carValue, boolean homeless,
                       StudentStatus studentStatus) {
        this.birthDate = birthDate;
        this.married = married;
        this.householdSize = householdSize;
        this.monthlyIncome = monthlyIncome;
        this.totalAssets = totalAssets;
        this.carValue = carValue;
        this.homeless = homeless;
        this.studentStatus = studentStatus == null ? StudentStatus.NONE : studentStatus;
    }

    public StudentStatus studentStatusOrNone() {
        return studentStatus == null ? StudentStatus.NONE : studentStatus;
    }

    public int age(LocalDate today) {
        return Period.between(birthDate, today).getYears();
    }
}
