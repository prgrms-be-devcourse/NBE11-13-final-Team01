package com.zipick.notice.domain;

import com.zipick.global.entity.BaseTimeEntity;
import com.zipick.notice.support.RegionNormalizer;
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

    /**
     * 지오코딩이 확인한 <b>표준 주소</b>("경북 영주시 대동로34번길 23-3"). {@link #enrichAddress}가 채운다.
     *
     * <p>⚠️ 중복 비교용 축약형(공백 제거·소문자)을 여기 넣지 말 것. 그 값에는 시도가 없을 수 있어
     * {@link #normalizeRegion()}이 시도를 복구할 근거를 잃는다. 중복 판정은 수집 쪽이 따로 계산한다.
     */
    @Column(name = "normalized_address", length = 500)
    private String normalizedAddress;

    @Column(name = "road_name", length = 255)
    private String roadName;

    @Column(name = "lot_address", length = 500)
    private String lotAddress;

    @Column(name = "road_address", length = 500)
    private String roadAddress;

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
    @Column(name = "raw_payload", columnDefinition = "jsonb")
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

    /** 운영자가 목록에서 숨기거나 다시 보이게 할 때. 다음 수집에서 목록에 다시 나타나면 synchronize 가 active 를 되살린다 */
    public void changeActive(boolean active) {
        this.active = active;
    }

    /** AI 검수기가 확정한 주택 기본 정보만 덮어씁니다. */
    public void applyAiNormalization(
            String name,
            String provinceName,
            String districtName,
            String address,
            String lotAddress,
            String roadAddress
    ) {
        this.name = keepExistingWhenBlank(name, this.name);
        this.provinceName = keepExistingWhenBlank(provinceName, this.provinceName);
        this.districtName = keepExistingWhenBlank(districtName, this.districtName);
        this.address = keepExistingWhenBlank(address, this.address);
        this.lotAddress = keepExistingWhenBlank(lotAddress, this.lotAddress);
        this.roadAddress = keepExistingWhenBlank(roadAddress, this.roadAddress);
        normalizeRegion();
    }

    public void enrichAddress(
            String normalizedAddress,
            String provinceName,
            String districtName,
            String legalDongName,
            String roadName,
            String buildingName,
            BigDecimal latitude,
            BigDecimal longitude
    ) {
        // address는 수집 원문이므로 변경하지 않는다. normalizedAddress만 카카오 표준 주소로 갱신한다.
        this.normalizedAddress = keepExistingWhenBlank(normalizedAddress, this.normalizedAddress);
        this.provinceName = fillWhenBlank(this.provinceName, provinceName);
        this.districtName = fillWhenBlank(this.districtName, districtName);
        this.legalDongName = fillWhenBlank(this.legalDongName, legalDongName);
        this.roadName = fillWhenBlank(this.roadName, roadName);
        this.name = fillWhenBlank(this.name, buildingName);
        if (this.latitude == null || this.longitude == null) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
        normalizeRegion();
    }

    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }

    public void synchronize(
            String pnu,
            String name,
            String provinceName,
            String districtName,
            String address,
            String normalizedAddress,
            String roadName,
            String lotAddress,
            String roadAddress,
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
        this.lotAddress = keepExistingWhenBlank(lotAddress, this.lotAddress);
        this.roadAddress = keepExistingWhenBlank(roadAddress, this.roadAddress);
        this.legalDongName = keepExistingWhenBlank(legalDongName, this.legalDongName);
        this.heatingMethod = keepExistingWhenBlank(heatingMethod, this.heatingMethod);
        this.totalHouseholdCount = totalHouseholdCount == null ? this.totalHouseholdCount : totalHouseholdCount;
        this.rawPayload = rawPayload == null ? this.rawPayload : rawPayload;
        normalizeRegion();
        markSeen(seenAt);
    }

    /**
     * 시도·시군구 표준화. **지역 값을 쓰는 세 경로(synchronize / enrichAddress / applyAiNormalization)가
     * 모두 마지막에 이것을 부른다.** 수집기마다 따로 정규화하지 않기 위한 공통 진입점이다.
     *
     * <p>이미 들어 있던 값도 다시 통과시킨다. enrichAddress 가 {@code fillWhenBlank} 라서
     * 기존 비표준 값을 Kakao 결과로 덮지 못하던 문제가 여기서 함께 풀린다.
     *
     * <p>판정 근거는 {@link RegionNormalizer} 참고. 근거가 없으면 바꾸지 않는다.
     */
    private void normalizeRegion() {
        RegionNormalizer.Region region =
                RegionNormalizer.normalize(this.provinceName, this.districtName, this.address, this.normalizedAddress);
        this.provinceName = region.province();
        this.districtName = region.district();
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
        synchronize(pnu, name, provinceName, districtName, address, normalizedAddress,
                roadName, null, null, legalDongName, heatingMethod, totalHouseholdCount,
                rawPayload, seenAt);
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

    private String fillWhenBlank(String existingValue, String fallbackValue) {
        return existingValue == null || existingValue.isBlank()
                ? keepExistingWhenBlank(fallbackValue, existingValue)
                : existingValue;
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

    public String getProvinceName() {
        return provinceName;
    }

    public String getDistrictName() {
        return districtName;
    }

    public String getAddress() {
        return address;
    }

    public String getNormalizedAddress() {
        return normalizedAddress;
    }

    public String getRoadName() {
        return roadName;
    }

    public String getLotAddress() {
        return lotAddress;
    }

    public String getRoadAddress() {
        return roadAddress;
    }

    public String getLegalDongName() {
        return legalDongName;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public String getHeatingMethod() {
        return heatingMethod;
    }

    public Integer getTotalHouseholdCount() {
        return totalHouseholdCount;
    }

    public Integer getBuildYear() {
        return buildYear;
    }

    public Integer getFloor() {
        return floor;
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
