package com.zipick.notice.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HousingGeocodingServiceTest {

    @Test
    void removesBuildingUnitFromAddressSearchQuery() {
        String query = HousingGeocodingService.toSearchQuery(
                "강원특별자치도 춘천시 퇴계로 123 (퇴계동) 101동 1203호"
        );

        assertThat(query).isEqualTo("강원특별자치도 춘천시 퇴계로 123");
    }

    @Test
    void keepsLotNumberInAddressSearchQuery() {
        String query = HousingGeocodingService.toSearchQuery(
                "충청남도 공주시 우금티로 709-12 302호"
        );

        assertThat(query).isEqualTo("충청남도 공주시 우금티로 709-12");
    }

    @Test
    void prioritizesRoadAddressWithoutLegalDongAndBuildingName() {
        List<String> queries = HousingGeocodingService.createSearchQueries(
                "경상북도 경산시 둥지로10길 28(임당동) 아너스2",
                "둥지로10길"
        );

        assertThat(queries).containsExactly(
                "경상북도 경산시 둥지로10길 28",
                "경상북도 경산시 둥지로10길 28 아너스2"
        );
    }

    @Test
    void supportsRoadNamesStartingWithANumber() {
        String query = HousingGeocodingService.toRoadAddressQuery(
                "경상북도 구미시 3공단1로 275-46(임수동), 비나리",
                "3공단1로"
        );

        assertThat(query).isEqualTo("경상북도 구미시 3공단1로 275-46");
    }
}
