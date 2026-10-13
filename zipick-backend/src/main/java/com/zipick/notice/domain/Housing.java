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
        name = "housing",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_housing_notice_source_key",
                columnNames = {"notice_id", "source_housing_key"}
        )
)
public class Housing extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "housing_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notice_id", nullable = false)
    private Notice notice;

    @Column(name = "source_housing_key", nullable = false, length = 500)
    private String sourceHousingKey;

    @Column(name = "pnu", length = 30)
    private String pnu;

    @Column(name = "name", length = 255)
    private String name;

    @Column(name = "province_name", length = 100)
    private String provinceName;

    @Column(name = "district_name", length = 100)
    private String districtName;

    @Column(name = "address", nullable = false, length = 500)
    private String address;

    @Column(name = "normalized_address", length = 500)
    private String normalizedAddress;

    @Column(name = "road_name", length = 255)
    private String roadName;

    @Column(name = "legal_dong_name", length = 255)
    private String legalDongName;

    @Column(name = "latitude", precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "heating_method", length = 100)
    private String heatingMethod;

    @Column(name = "total_household_count")
    private Integer totalHouseholdCount;

    @Column(name = "build_year")
    private Integer buildYear;

    @Column(name = "floor")
    private Integer floor;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "missing_count", nullable = false)
    private int missingCount;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "last_missing_checked_on")
    private LocalDate lastMissingCheckedOn;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "json")
    private String rawPayload;

    protected Housing() {
    }

    public Housing(Notice notice, String sourceHousingKey, String name, String address) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("주택 주소는 비어 있을 수 없습니다.");
        }
        this.notice = notice;
        this.sourceHousingKey = sourceHousingKey;
        this.name = name;
        this.address = address.trim();
        this.active = true;
        this.lastSeenAt = Instant.now();
    }

    public void updateCoordinates(BigDecimal latitude, BigDecimal longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public void synchronize(
            String pnu,
            String name,
            String provinceName,
            String districtName,
            String address,
            String normalizedAddress,
            String roadName,
            String legalDongName,
            String heatingMethod,
            Integer totalHouseholdCount,
            String rawPayload,
            Instant seenAt
    ) {
        this.pnu = keepExistingWhenBlank(pnu, this.pnu);
        this.name = keepExistingWhenBlank(name, this.name);
        this.provinceName = keepExistingWhenBlank(provinceName, this.provinceName);
        this.districtName = keepExistingWhenBlank(districtName, this.districtName);
        this.address = keepExistingWhenBlank(address, this.address);
        this.normalizedAddress = keepExistingWhenBlank(normalizedAddress, this.normalizedAddress);
        this.roadName = keepExistingWhenBlank(roadName, this.roadName);
        this.legalDongName = keepExistingWhenBlank(legalDongName, this.legalDongName);
        this.heatingMethod = keepExistingWhenBlank(heatingMethod, this.heatingMethod);
        this.totalHouseholdCount = totalHouseholdCount == null ? this.totalHouseholdCount : totalHouseholdCount;
        this.rawPayload = rawPayload == null ? this.rawPayload : rawPayload;
        markSeen(seenAt);
    }

    public void markSeen(Instant seenAt) {
        this.active = true;
        this.missingCount = 0;
        this.lastSeenAt = seenAt;
        this.lastMissingCheckedOn = null;
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

    public Notice getNotice() {
        return notice;
    }

    public String getSourceHousingKey() {
        return sourceHousingKey;
    }

    public String getPnu() {
        return pnu;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

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
