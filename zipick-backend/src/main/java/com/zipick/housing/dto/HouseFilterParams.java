package com.zipick.housing.dto;

import com.zipick.housing.domain.Agency;
import com.zipick.housing.domain.AnnouncementStatus;
import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import lombok.Data;

/**
 * 공급주택 필터 쿼리 파라미터. 전체 공급주택 조회와 추천 목록이 같은 파라미터를 쓴다.
 * 여러 값은 쉼표로 구분한다. 예: ?rentalTypes=PURCHASE,HAPPY&depositMax=3000
 *
 * 필터는 사용자의 저장된 추천조건을 바꾸지 않고, 지금 화면에 보이는 결과만 좁힌다.
 */
@Data
public class HouseFilterParams {
    private List<String> regions;        // 시군구 코드
    private Integer depositMin;
    private Integer depositMax;
    private Integer rentMin;
    private Integer rentMax;
    private BigDecimal areaMin;
    private BigDecimal areaMax;
    private List<String> housingTypes;
    private List<String> statuses;       // 비우면 NOTICE,RECEIVING (= 모집 상태 '전체')
    private List<String> agencies;
    private List<String> rentalTypes;
    private Boolean includeUnknown = true;

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
