package com.zipick.recommendation.dto;

import com.zipick.housing.domain.Agency;
import com.zipick.housing.domain.AnnouncementStatus;
import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import com.zipick.housing.dto.HouseFilter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import lombok.Data;

/**
 * 추천 목록 조회 시 쓰는 필터 쿼리 파라미터.
 * 여러 값은 쉼표로 구분한다. 예: ?rentalTypes=PURCHASE,HAPPY&depositMax=3000
 */
@Data
public class RecommendationFilterParams {
    private List<String> regions;        // 시군구 코드
    private Integer depositMin;
    private Integer depositMax;
    private Integer rentMin;
    private Integer rentMax;
    private BigDecimal areaMin;
    private BigDecimal areaMax;
    private List<String> housingTypes;
    private List<String> statuses;       // 기본 NOTICE,RECEIVING
    private List<String> agencies;
    private List<String> rentalTypes;
    private Boolean includeUnknown = true;

    /**
     * true면 기준 점수·최대 개수를 적용하지 않고 후보 전체(최대 200)를 점수와 함께 돌려준다.
     * 프론트가 가중치 슬라이더를 움직일 때 서버 없이 다시 순위를 매기는 데 쓴다.
     */
    private Boolean preview = false;

    public boolean isPreview() {
        return Boolean.TRUE.equals(preview);
    }

    public HouseFilter toFilter() {
        Set<AnnouncementStatus> status = HouseFilter.parseEnums(statuses, AnnouncementStatus.class);
        return HouseFilter.builder()
                .sigunguCodes(HouseFilter.parseStrings(regions))
                .depositMin(depositMin).depositMax(depositMax)
                .rentMin(rentMin).rentMax(rentMax)
                .areaMin(areaMin).areaMax(areaMax)
                .housingTypes(HouseFilter.parseEnums(housingTypes, HousingType.class))
                .statuses(status.isEmpty() ? Set.of(AnnouncementStatus.NOTICE, AnnouncementStatus.RECEIVING) : status)
                .agencies(HouseFilter.parseEnums(agencies, Agency.class))
                .rentalTypes(HouseFilter.parseEnums(rentalTypes, RentalType.class))
                .includeUnknown(includeUnknown == null || includeUnknown)
                .build();
    }
}
