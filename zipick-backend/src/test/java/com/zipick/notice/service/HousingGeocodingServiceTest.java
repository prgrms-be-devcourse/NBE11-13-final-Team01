package com.zipick.notice.service;

import com.zipick.external.kakao.client.KakaoAddressClient;
import com.zipick.external.kakao.config.KakaoLocalApiProperties;
import com.zipick.external.kakao.dto.KakaoAddressSearchResponse;
import com.zipick.external.kakao.dto.KakaoKeywordSearchResponse;
import com.zipick.notice.domain.Housing;
import com.zipick.notice.domain.Notice;
import com.zipick.notice.domain.NoticeStatus;
import com.zipick.notice.repository.HousingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HousingGeocodingServiceTest {

    private final HousingRepository repository = mock(HousingRepository.class);
    private final KakaoAddressClient client = mock(KakaoAddressClient.class);
    private final HousingGeocodingPersistenceService persistence = mock(HousingGeocodingPersistenceService.class);

    @Test
    void skipsCollectionWhenKakaoKeyIsNotConfigured() {
        HousingGeocodingService service = service(new KakaoLocalApiProperties("YOUR_KEY", null));

        HousingGeocodingService.GeocodingResult result = service.enrichMissingCoordinates();

        assertThat(result.skipped()).isTrue();
        verify(repository, never()).findActiveWithoutCoordinates();
    }

    @Test
    void resolvesUniqueAddressAndCountsExcludedPlaceholder() {
        Housing resolved = housing(1L, "서울특별시 강남구 테헤란로 10", "테헤란로");
        Housing excluded = housing(2L, "서울특별시 00구 0000로 000", "0000로");
        when(repository.findActiveWithoutCoordinates()).thenReturn(List.of(resolved, excluded));
        when(client.search(anyString())).thenReturn(new KakaoAddressSearchResponse(List.of(
                document("127.1", "37.5", true, true)
        )));
        when(persistence.updateIfCoordinatesMissing(eq(1L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);

        HousingGeocodingService.GeocodingResult result = service(configured()).enrichMissingCoordinates();

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.excluded()).isEqualTo(1);
        assertThat(result.unresolved()).isZero();
    }

    @Test
    void fallsBackToKeywordAndFiltersInvalidOrDuplicateCandidates() {
        Housing housing = housing(3L, "서울 강남구 테헤란로 10", null);
        when(repository.findActiveWithoutCoordinates()).thenReturn(List.of(housing));
        when(client.search(anyString())).thenReturn(new KakaoAddressSearchResponse(List.of()));
        when(client.searchKeyword(anyString())).thenReturn(new KakaoKeywordSearchResponse(List.of(
                keyword(null, "서울 강남구 테헤란로 10", "", "127", "37"),
                keyword("강남사업단", "서울 강남구 테헤란로 10", "", "127", "37"),
                keyword("테스트아파트", "다른 주소", "", "127", "37"),
                keyword("테스트아파트", "", "서울 강남구 테헤란로 10", "127.1", "37.5"),
                keyword("테스트아파트", "", "서울 강남구 테헤란로 10", "127.1", "37.5")
        )));
        when(persistence.updateIfCoordinatesMissing(eq(3L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);

        HousingGeocodingService.GeocodingResult result = service(configured()).enrichMissingCoordinates();

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.apiCalls()).isEqualTo(2); // 주소 검색 1개 + 키워드 보완 1개
    }

    @Test
    void leavesAmbiguousOrCoordinateLessResultsUnresolved() {
        Housing housing = housing(4L, "제주특별자치도 제주시 연동 1, 2", null);
        when(repository.findActiveWithoutCoordinates()).thenReturn(List.of(housing));
        when(client.search(anyString())).thenReturn(
                new KakaoAddressSearchResponse(List.of(document("", "", false, false))),
                new KakaoAddressSearchResponse(List.of(document("127", "37", false, false), document("128", "38", false, false)))
        );
        when(client.searchKeyword(anyString())).thenReturn(new KakaoKeywordSearchResponse(List.of(
                keyword("테스트빌라", "제주 제주시 연동 1", "", "", "")
        )));

        HousingGeocodingService.GeocodingResult result = service(configured()).enrichMissingCoordinates();

        assertThat(result.unresolved()).isEqualTo(1);
        assertThat(result.updated()).isZero();
    }

    @Test
    void stopsRemainingWorkWhenAddressOrKeywordApiFails() {
        Housing first = housing(5L, "부산광역시 해운대구 우동 1", null);
        Housing second = housing(6L, "부산광역시 해운대구 우동 2", null);
        when(repository.findActiveWithoutCoordinates()).thenReturn(List.of(first, second));
        when(client.search(anyString())).thenThrow(new IllegalStateException("timeout"));

        HousingGeocodingService.GeocodingResult addressFailure = service(configured()).enrichMissingCoordinates();
        assertThat(addressFailure.unresolved()).isEqualTo(2);

        reset(client);
        when(client.search(anyString())).thenReturn(new KakaoAddressSearchResponse(List.of()));
        when(client.searchKeyword(anyString())).thenThrow(new IllegalStateException("timeout"));
        HousingGeocodingService.GeocodingResult keywordFailure = service(configured()).enrichMissingCoordinates();
        assertThat(keywordFailure.unresolved()).isEqualTo(2);
    }

    @Test
    void buildsNormalizedSearchVariants() {
        assertThat(HousingGeocodingService.createSearchQueries(null, null)).isEmpty();
        assertThat(HousingGeocodingService.createSearchQueries("서울특별시 강남구 강남구 역삼동 1", null))
                .contains("서울특별시 강남구 역삼동 1", "강남구 역삼동 1");
        assertThat(HousingGeocodingService.createSearchQueries("경기도 수원시 원천동 295-1, 295-5", null))
                .contains("경기도 수원시 원천동 295-1", "수원시 원천동 295-5");
        assertThat(HousingGeocodingService.toRoadAddressQuery("서울 강남구 테헤란로 10 101호", "테헤란로"))
                .isEqualTo("서울 강남구 테헤란로 10");
        assertThat(HousingGeocodingService.toRoadAddressQuery("서울 강남구", "테헤란로")).isNull();
        assertThat(HousingGeocodingService.toSearchQuery("서울 강남구(역삼동) 테헤란로 10 101호"))
                .isEqualTo("서울 강남구 테헤란로 10");
    }

    private HousingGeocodingService service(KakaoLocalApiProperties properties) {
        return new HousingGeocodingService(repository, client, properties, persistence);
    }

    private KakaoLocalApiProperties configured() {
        return new KakaoLocalApiProperties("key", "https://dapi.kakao.com");
    }

    private Housing housing(long id, String address, String roadName) {
        Housing housing = new Housing(new Notice("LH", "n" + id, "공고", NoticeStatus.OPEN),
                "h" + id, null, address);
        ReflectionTestUtils.setField(housing, "id", id);
        if (roadName != null) {
            housing.synchronize(null, null, null, null, address, null, roadName,
                    null, null, null, null, null, null, java.time.Instant.now());
        }
        return housing;
    }

    private KakaoAddressSearchResponse.Document document(String x, String y, boolean address, boolean road) {
        return new KakaoAddressSearchResponse.Document(
                x, y,
                address ? new KakaoAddressSearchResponse.Address("서울 강남구 역삼동 1", "서울", "강남구", "역삼동") : null,
                road ? new KakaoAddressSearchResponse.RoadAddress("서울 강남구 테헤란로 10", "서울", "강남구", "역삼동", "테헤란로", "테스트빌딩") : null
        );
    }

    private KakaoKeywordSearchResponse.Document keyword(
            String name, String address, String roadAddress, String x, String y
    ) {
        return new KakaoKeywordSearchResponse.Document(name, address, roadAddress, x, y);
    }
}
