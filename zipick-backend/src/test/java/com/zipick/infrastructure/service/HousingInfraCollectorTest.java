package com.zipick.infrastructure.service;

import com.zipick.external.kakao.client.KakaoCategorySearchClient;
import com.zipick.external.kakao.dto.KakaoCategorySearchResponse;
import com.zipick.external.kakao.dto.KakaoCategorySearchResponse.Document;
import com.zipick.external.kakao.dto.KakaoCategorySearchResponse.Meta;
import com.zipick.infrastructure.config.InfraPolicy;
import com.zipick.infrastructure.config.InfraProperties;
import com.zipick.infrastructure.config.InfraProperties.Category;
import com.zipick.infrastructure.service.InfraCollectionResult.CollectedCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class HousingInfraCollectorTest {

    private static final Category SUBWAY = new Category("SUBWAY", "SW8", "지하철역", 1500);
    private static final Category MART = new Category("MART", "MT1", "대형마트", 1000);
    private static final BigDecimal LATITUDE = new BigDecimal("37.5756000");
    private static final BigDecimal LONGITUDE = new BigDecimal("127.0250000");

    @Mock
    private KakaoCategorySearchClient client;

    private HousingInfraCollector collector;

    @BeforeEach
    void setUp() {
        InfraPolicy policy = new InfraPolicy(new InfraProperties(null, null, List.of(SUBWAY, MART)));
        collector = new HousingInfraCollector(client, policy);
    }

    @Test
    @DisplayName("카테고리마다 최근접 시설 1곳과 전체 검색 수를 모은다")
    void collectsNearestPlacePerCategory() {
        given(client.search(eq("SW8"), any(), any(), eq(1500)))
                .willReturn(response(6, document("신설동역", "683")));
        given(client.search(eq("MT1"), any(), any(), eq(1000)))
                .willReturn(response(2, document("○○마트", "750")));

        InfraCollectionResult result = collector.collect(1L, LATITUDE, LONGITUDE);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.apiCalls()).isEqualTo(2);
        assertThat(result.categories()).hasSize(2);

        CollectedCategory subway = result.categories().getFirst();
        assertThat(subway.category()).isEqualTo(SUBWAY);
        assertThat(subway.searchCount()).isEqualTo(6);
        assertThat(subway.nearest().name()).isEqualTo("신설동역");
        assertThat(subway.nearest().distanceMeter()).isEqualTo(683);
    }

    @Test
    @DisplayName("반경 내 0건은 정상 결과로 두고 시설 정보를 만들지 않는다")
    void treatsZeroResultAsSuccess() {
        given(client.search(eq("SW8"), any(), any(), anyInt()))
                .willReturn(new KakaoCategorySearchResponse(new Meta(0, 0, true), List.of()));
        given(client.search(eq("MT1"), any(), any(), anyInt()))
                .willReturn(response(2, document("○○마트", "750")));

        InfraCollectionResult result = collector.collect(1L, LATITUDE, LONGITUDE);

        assertThat(result.isSuccess()).isTrue();
        CollectedCategory subway = result.categories().getFirst();
        assertThat(subway.hasPlace()).isFalse();
        assertThat(subway.searchCount()).isZero();
    }

    @Test
    @DisplayName("호출이 실패하면 API_ERROR 로 구분하고 남은 카테고리는 호출하지 않는다")
    void returnsApiErrorWhenCallFails() {
        given(client.search(eq("SW8"), any(), any(), anyInt()))
                .willThrow(new IllegalStateException("connection reset"));

        InfraCollectionResult result = collector.collect(1L, LATITUDE, LONGITUDE);

        assertThat(result.status()).isEqualTo(InfraCollectionResult.Status.API_ERROR);
        assertThat(result.categories()).isEmpty();
        verify(client).search(eq("SW8"), any(), any(), anyInt());
        verifyNoMoreInteractions(client);
    }

    @Test
    @DisplayName("전체 수가 0이 아닌데 문서가 없으면 0건이 아니라 DATA_ERROR 로 본다")
    void doesNotTreatBrokenResponseAsZeroResult() {
        given(client.search(eq("SW8"), any(), any(), anyInt()))
                .willReturn(new KakaoCategorySearchResponse(new Meta(5, 5, true), List.of()));

        InfraCollectionResult result = collector.collect(1L, LATITUDE, LONGITUDE);

        assertThat(result.status()).isEqualTo(InfraCollectionResult.Status.DATA_ERROR);
    }

    @Test
    @DisplayName("meta 가 없거나 거리를 해석할 수 없으면 DATA_ERROR 로 본다")
    void returnsDataErrorWhenResponseIsUnusable() {
        given(client.search(eq("SW8"), any(), any(), anyInt()))
                .willReturn(response(3, document("신설동역", "거리없음")));

        InfraCollectionResult result = collector.collect(1L, LATITUDE, LONGITUDE);

        assertThat(result.status()).isEqualTo(InfraCollectionResult.Status.DATA_ERROR);
    }

    private static KakaoCategorySearchResponse response(int totalCount, Document document) {
        return new KakaoCategorySearchResponse(new Meta(totalCount, totalCount, true), List.of(document));
    }

    private static Document document(String name, String distance) {
        return new Document("1", name, "지번주소", "도로명주소",
                "127.0250", "37.5756", distance, "http://place.map.kakao.com/1", null);
    }
}
