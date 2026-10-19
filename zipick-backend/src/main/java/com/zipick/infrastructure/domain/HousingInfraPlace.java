package com.zipick.infrastructure.domain;

import com.zipick.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 카테고리별 '가장 가까운 시설' 1곳. 스냅샷당 카테고리마다 0행 또는 1행이다.
 *
 * 반경 내 결과가 0건인 카테고리는 행을 만들지 않는다.
 * searchCount 는 저장한 행 수가 아니라 카카오 meta.total_count(반경 내 전체 시설 수)다.
 */
@Entity
@Getter
@Table(
        name = "housing_infra_place",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_housing_infra_place_category",
                columnNames = {"housing_infra_snapshot_id", "category_code"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HousingInfraPlace extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "housing_infra_place_id")
    private Long id;

    @Column(name = "housing_infra_snapshot_id", nullable = false)
    private Long snapshotId;

    /** 서비스 카테고리 코드 (SUBWAY, MART ...) */
    @Column(name = "category_code", nullable = false, length = 30)
    private String categoryCode;

    /** 반경 내 해당 카테고리 전체 시설 수 (카카오 meta.total_count) */
    @Column(name = "search_count", nullable = false)
    private int searchCount;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** 카카오가 돌려준 직선거리(m). 실제 도보 경로가 아니다. */
    @Column(name = "distance_meter", nullable = false)
    private int distanceMeter;

    // --- 아래는 현재 화면에서 쓰지 않지만, 나중에 지도 마커·카카오맵 링크를 붙일 때 재수집이 없도록 함께 저장한다 ---

    @Column(name = "kakao_place_id", length = 50)
    private String kakaoPlaceId;

    @Column(name = "road_address", length = 500)
    private String roadAddress;

    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "latitude", precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "place_url", length = 2000)
    private String placeUrl;

    @Builder
    public HousingInfraPlace(
            Long snapshotId,
            String categoryCode,
            int searchCount,
            String name,
            int distanceMeter,
            String kakaoPlaceId,
            String roadAddress,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            String placeUrl
    ) {
        this.snapshotId = snapshotId;
        this.categoryCode = categoryCode;
        this.searchCount = searchCount;
        this.name = name;
        this.distanceMeter = distanceMeter;
        this.kakaoPlaceId = kakaoPlaceId;
        this.roadAddress = roadAddress;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.placeUrl = placeUrl;
    }
}
