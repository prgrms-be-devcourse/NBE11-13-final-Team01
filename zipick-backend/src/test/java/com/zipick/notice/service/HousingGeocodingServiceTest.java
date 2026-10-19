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
                "경산시 둥지로10길 28",
                "경상북도 경산시 둥지로10길 28 아너스2",
                "경산시 둥지로10길 28 아너스2"
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

    @Test
    void removesRepeatedAdministrativeSegments() {
        List<String> queries = HousingGeocodingService.createSearchQueries(
                "경기도 수원시 영통구 원천동 영통구 원천동 295-1,295-5",
                null
        );

        assertThat(queries).containsExactly(
                "경기도 수원시 영통구 원천동 295-1",
                "수원시 영통구 원천동 295-1",
                "경기도 수원시 영통구 원천동 295-5",
                "수원시 영통구 원천동 295-5"
        );
    }

    @Test
    void removesRepeatedProvinceAndAddsShorterFallback() {
        List<String> queries = HousingGeocodingService.createSearchQueries(
                "서울특별시 서울특별시 광진구 군자동 36-26",
                null
        );

        assertThat(queries).containsExactly(
                "서울특별시 광진구 군자동 36-26",
                "광진구 군자동 36-26"
        );
    }

    @Test
    void skipsTemplateAddress() {
        assertThat(HousingGeocodingService.createSearchQueries(
                "서울특별시 00구 0000로 000", "0000로"
        )).isEmpty();
    }
}
