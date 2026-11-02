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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(
        name = "housing_supply",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_housing_supply_source_key",
                columnNames = {"housing_id", "source_supply_key"}
        )
)
public class HousingSupply extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "housing_supply_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "housing_id", nullable = false)
    private Housing housing;

    @Column(name = "source_supply_key", nullable = false, length = 500)
    private String sourceSupplyKey;

    @Column(name = "external_house_id")
    private Long externalHouseId;

    @Column(name = "housing_type", length = 100)
    private String housingType;

    @Column(name = "supply_type", length = 100)
    private String supplyType;

    @Column(name = "building_dong", length = 100)
    private String buildingDong;

    @Column(name = "unit_number", length = 100)
    private String unitNumber;

    @Column(name = "floor")
    private Integer floor;

    @Column(name = "room_count")
    private Integer roomCount;

    @Column(name = "source_row_number")
    private Integer sourceRowNumber;

    @Column(name = "supply_count")
    private Integer supplyCount;

    @Column(name = "supply_house_count")
    private Integer supplyHouseCount;

    @Column(name = "rent_deposit")
    private Long rentDeposit;

    @Column(name = "contract_deposit")
    private Long contractDeposit;

    @Column(name = "interim_payment")
    private Long interimPayment;

    @Column(name = "balance")
    private Long balance;

    @Column(name = "monthly_rent")
    private Long monthlyRent;

    @Column(name = "exclusive_area", precision = 10, scale = 2)
    private BigDecimal exclusiveArea;

    @Column(name = "subway_walk_distance_km", precision = 8, scale = 3)
    private BigDecimal subwayWalkDistanceKm;

    @Column(name = "supply_status", length = 50)
    private String supplyStatus;

    @Column(name = "nearby_subway_station", length = 200)
    private String nearbySubwayStation;

    @Column(name = "elevator_available")
    private Boolean elevatorAvailable;

    @Column(name = "community_facility_available")
    private Boolean communityFacilityAvailable;

    @Column(name = "total_parking_spaces")
    private Integer totalParkingSpaces;

    @Column(name = "mechanical_parking_available")
    private Boolean mechanicalParkingAvailable;

    @Column(name = "mechanical_parking_spaces")
    private Integer mechanicalParkingSpaces;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "missing_count", nullable = false)
    private int missingCount;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "last_missing_checked_on")
    private LocalDate lastMissingCheckedOn;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb")
    private String rawPayload;

    protected HousingSupply() {
    }

    public HousingSupply(Housing housing, String sourceSupplyKey, Long externalHouseId) {
        this.housing = housing;
        this.sourceSupplyKey = sourceSupplyKey;
        this.externalHouseId = externalHouseId;
        this.active = true;
        this.lastSeenAt = Instant.now();
    }

    public void synchronize(
            Long externalHouseId,
            String housingType,
            String supplyType,
            String buildingDong,
            String unitNumber,
            Integer floor,
            Integer roomCount,
            Integer sourceRowNumber,
            BigDecimal exclusiveArea,
            Integer supplyCount,
            Integer supplyHouseCount,
            Long rentDeposit,
            Long contractDeposit,
            Long interimPayment,
            Long balance,
            Long monthlyRent,
            String supplyStatus,
            String nearbySubwayStation,
            BigDecimal subwayWalkDistanceKm,
            Boolean elevatorAvailable,
            Boolean communityFacilityAvailable,
            Integer totalParkingSpaces,
            Boolean mechanicalParkingAvailable,
            Integer mechanicalParkingSpaces,
            String rawPayload,
            Instant seenAt
    ) {
        this.externalHouseId = externalHouseId == null ? this.externalHouseId : externalHouseId;
        this.housingType = keepExistingWhenBlank(housingType, this.housingType);
        this.supplyType = keepExistingWhenBlank(supplyType, this.supplyType);
        this.buildingDong = keepExistingWhenBlank(buildingDong, this.buildingDong);
        this.unitNumber = keepExistingWhenBlank(unitNumber, this.unitNumber);
        this.floor = floor == null ? this.floor : floor;
        this.roomCount = roomCount == null ? this.roomCount : roomCount;
        this.sourceRowNumber = sourceRowNumber == null ? this.sourceRowNumber : sourceRowNumber;
        this.exclusiveArea = exclusiveArea == null ? this.exclusiveArea : exclusiveArea;
        this.supplyCount = supplyCount == null ? this.supplyCount : supplyCount;
        this.supplyHouseCount = supplyHouseCount == null ? this.supplyHouseCount : supplyHouseCount;
        this.rentDeposit = rentDeposit == null ? this.rentDeposit : rentDeposit;
        this.contractDeposit = contractDeposit == null ? this.contractDeposit : contractDeposit;
        this.interimPayment = interimPayment == null ? this.interimPayment : interimPayment;
        this.balance = balance == null ? this.balance : balance;
        this.monthlyRent = monthlyRent == null ? this.monthlyRent : monthlyRent;
        this.supplyStatus = keepExistingWhenBlank(supplyStatus, this.supplyStatus);
        this.nearbySubwayStation = keepExistingWhenBlank(nearbySubwayStation, this.nearbySubwayStation);
        this.subwayWalkDistanceKm = subwayWalkDistanceKm == null
                ? this.subwayWalkDistanceKm : subwayWalkDistanceKm;
        this.elevatorAvailable = elevatorAvailable == null ? this.elevatorAvailable : elevatorAvailable;
        this.communityFacilityAvailable = communityFacilityAvailable == null
                ? this.communityFacilityAvailable : communityFacilityAvailable;
        this.totalParkingSpaces = totalParkingSpaces == null
                ? this.totalParkingSpaces : totalParkingSpaces;
        this.mechanicalParkingAvailable = mechanicalParkingAvailable == null
                ? this.mechanicalParkingAvailable : mechanicalParkingAvailable;
        this.mechanicalParkingSpaces = mechanicalParkingSpaces == null
                ? this.mechanicalParkingSpaces : mechanicalParkingSpaces;
        this.rawPayload = rawPayload == null ? this.rawPayload : rawPayload;
        markSeen(seenAt);
    }

    public void markSeen(Instant seenAt) {
        this.active = true;
        this.missingCount = 0;
        this.lastSeenAt = seenAt;
        this.lastMissingCheckedOn = null;
    }

    /** AI 검수기가 확정한 공급 정보만 선택적으로 반영합니다. */
    public void applyAiNormalization(
            String housingType,
            String supplyType,
            String buildingDong,
            String unitNumber,
            Integer floor,
            Integer roomCount,
            BigDecimal exclusiveArea,
            Long rentDeposit,
            Long monthlyRent,
            Integer supplyCount,
            String supplyStatus,
            Boolean elevatorAvailable,
            Boolean communityFacilityAvailable,
            Integer totalParkingSpaces,
            Boolean mechanicalParkingAvailable,
            Integer mechanicalParkingSpaces,
            String rawPayload
    ) {
        this.housingType = keepExistingWhenBlank(housingType, this.housingType);
        this.supplyType = keepExistingWhenBlank(supplyType, this.supplyType);
        this.buildingDong = keepExistingWhenBlank(buildingDong, this.buildingDong);
        this.unitNumber = keepExistingWhenBlank(unitNumber, this.unitNumber);
        this.floor = floor == null ? this.floor : floor;
        this.roomCount = roomCount == null ? this.roomCount : roomCount;
        this.exclusiveArea = exclusiveArea == null ? this.exclusiveArea : exclusiveArea;
        this.rentDeposit = rentDeposit == null ? this.rentDeposit : rentDeposit;
        this.monthlyRent = monthlyRent == null ? this.monthlyRent : monthlyRent;
        this.supplyCount = supplyCount == null ? this.supplyCount : supplyCount;
        this.supplyStatus = keepExistingWhenBlank(supplyStatus, this.supplyStatus);
        this.elevatorAvailable = elevatorAvailable == null ? this.elevatorAvailable : elevatorAvailable;
        this.communityFacilityAvailable = communityFacilityAvailable == null
                ? this.communityFacilityAvailable : communityFacilityAvailable;
        this.totalParkingSpaces = totalParkingSpaces == null ? this.totalParkingSpaces : totalParkingSpaces;
        this.mechanicalParkingAvailable = mechanicalParkingAvailable == null
                ? this.mechanicalParkingAvailable : mechanicalParkingAvailable;
        this.mechanicalParkingSpaces = mechanicalParkingSpaces == null
                ? this.mechanicalParkingSpaces : mechanicalParkingSpaces;
        this.rawPayload = rawPayload == null ? this.rawPayload : rawPayload;
    }

    public boolean markMissing(LocalDate checkedOn, int inactiveThreshold) {
        if (checkedOn.equals(lastMissingCheckedOn)) {
            return false;
        }
        this.lastMissingCheckedOn = checkedOn;
        this.missingCount++;
        if (missingCount >= inactiveThreshold) {
            boolean becameInactive = active;
            this.active = false;
            return becameInactive;
        }
        return false;
    }

    private String keepExistingWhenBlank(String newValue, String existingValue) {
        return newValue == null || newValue.isBlank() ? existingValue : newValue.trim();
    }

    public Long getId() {
        return id;
    }

    public Housing getHousing() {
        return housing;
    }

    public String getSourceSupplyKey() {
        return sourceSupplyKey;
    }

    public Long getExternalHouseId() {
        return externalHouseId;
    }

    public String getHousingType() {
        return housingType;
    }

    public String getSupplyType() {
        return supplyType;
    }

    public Long getRentDeposit() {
        return rentDeposit;
    }

    public Long getMonthlyRent() {
        return monthlyRent;
    }

    public void clearMonthlyRentIfEqualTo(Long incorrectAmount) {
        if (incorrectAmount != null && incorrectAmount.equals(monthlyRent)) monthlyRent = null;
    }

    public void clearUnitNumber() { unitNumber = null; }

    public void clearSupplyCounts() { supplyCount = null; supplyHouseCount = null; }

    public Integer getSupplyCount() {
        return supplyCount;
    }

    public Integer getSupplyHouseCount() {
        return supplyHouseCount;
    }

    public String getBuildingDong() {
        return buildingDong;
    }

    public String getUnitNumber() {
        return unitNumber;
    }

    public Integer getFloor() {
        return floor;
    }

    public Integer getRoomCount() {
        return roomCount;
    }

    public Integer getSourceRowNumber() { return sourceRowNumber; }

    public BigDecimal getExclusiveArea() {
        return exclusiveArea;
    }

    public String getSupplyStatus() { return supplyStatus; }
    public String getNearbySubwayStation() { return nearbySubwayStation; }
    public BigDecimal getSubwayWalkDistanceKm() { return subwayWalkDistanceKm; }
    public Boolean getElevatorAvailable() { return elevatorAvailable; }
    public Boolean getCommunityFacilityAvailable() { return communityFacilityAvailable; }
    public Integer getTotalParkingSpaces() { return totalParkingSpaces; }
    public Boolean getMechanicalParkingAvailable() { return mechanicalParkingAvailable; }
    public Integer getMechanicalParkingSpaces() { return mechanicalParkingSpaces; }
    public String getRawPayload() { return rawPayload; }

    public boolean isActive() {
        return active;
    }

    public int getMissingCount() {
        return missingCount;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public LocalDate getLastMissingCheckedOn() {
        return lastMissingCheckedOn;
    }
}
