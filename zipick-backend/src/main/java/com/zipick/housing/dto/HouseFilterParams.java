package com.zipick.housing.dto;

import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import com.zipick.notice.domain.NoticeStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import lombok.Data;

/**
 * 공급주택 필터 쿼리 파라미터. 전체 공급주택 조회와 추천 목록이 같은 파라미터를 쓴다.
 * 여러 값은 쉼표로 구분한다. 예: ?rentalTypes=HAPPY,NATIONAL&depositMax=3000&regions=경상북도 구미시
 *
 * 필터는 사용자의 저장된 추천조건을 바꾸지 않고, 지금 화면에 보이는 결과만 좁힌다.
 */
@Data
public class HouseFilterParams {
    private List<String> regions;        // "시도 시군구"
    private Integer depositMin;          // 만 원
    private Integer depositMax;
    private Integer rentMin;
    private Integer rentMax;
    private BigDecimal areaMin;
    private BigDecimal areaMax;
    private List<String> housingTypes;
    private List<String> statuses;       // UPCOMING, OPEN, CLOSED. 비우면 UPCOMING,OPEN (= 모집 상태 '전체')
    private List<String> agencies;       // 기관 코드
    private List<String> rentalTypes;
    private Boolean includeUnknown = true;

    public HouseFilter toFilter() {
        Set<NoticeStatus> status = HouseFilter.parseEnums(statuses, NoticeStatus.class);
        return HouseFilter.builder()
                .regions(HouseFilter.parseStrings(regions))
                .depositMin(depositMin).depositMax(depositMax)
                .rentMin(rentMin).rentMax(rentMax)
                .areaMin(areaMin).areaMax(areaMax)
                .housingTypes(HouseFilter.parseEnums(housingTypes, HousingType.class))
                .statuses(status.isEmpty() ? HouseFilter.OPEN_STATUSES : status)
                .agencies(HouseFilter.parseStrings(agencies))
                .rentalTypes(HouseFilter.parseEnums(rentalTypes, RentalType.class))
                .includeUnknown(includeUnknown == null || includeUnknown)
                .build();
    }
}
