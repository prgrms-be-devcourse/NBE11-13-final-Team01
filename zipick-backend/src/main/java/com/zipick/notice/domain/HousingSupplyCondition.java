package com.zipick.notice.domain;

import com.zipick.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
        name = "housing_supply_condition",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_housing_supply_condition",
                columnNames = {"housing_supply_id", "condition_code"}
        )
)
public class HousingSupplyCondition extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "housing_supply_condition_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "housing_supply_id", nullable = false)
    private HousingSupply housingSupply;

    @Column(name = "condition_code", nullable = false, length = 100)
    private String conditionCode;

    @Column(name = "condition_name", nullable = false, length = 300)
    private String conditionName;

    @Column(name = "rent_deposit")
    private Long rentDeposit;

    @Column(name = "monthly_rent")
    private Long monthlyRent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb")
    private String rawPayload;

    protected HousingSupplyCondition() {
    }

    public HousingSupplyCondition(HousingSupply housingSupply, String conditionCode, String conditionName) {
        this.housingSupply = housingSupply;
        this.conditionCode = conditionCode;
        this.conditionName = conditionName;
    }

    public void synchronize(String conditionName, Long rentDeposit, Long monthlyRent, String rawPayload) {
        if (conditionName != null && !conditionName.isBlank()) {
            this.conditionName = conditionName.trim();
        }
        this.rentDeposit = rentDeposit == null ? this.rentDeposit : rentDeposit;
        this.monthlyRent = monthlyRent == null ? this.monthlyRent : monthlyRent;
        this.rawPayload = rawPayload == null ? this.rawPayload : rawPayload;
    }

    public String getConditionCode() { return conditionCode; }
    public Long getId() { return id; }
    public HousingSupply getHousingSupply() { return housingSupply; }
    public String getConditionName() { return conditionName; }
    public Long getRentDeposit() { return rentDeposit; }
    public Long getMonthlyRent() { return monthlyRent; }
}
