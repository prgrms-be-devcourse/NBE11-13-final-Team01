package com.zipick.infrastructure.service;

import com.zipick.infrastructure.config.InfraProperties.Category;
import com.zipick.infrastructure.service.InfraCollectionResult.CollectedCategory;
import com.zipick.infrastructure.service.InfraCollectionResult.NearestPlace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HousingInfraPersistenceServiceTest {

    private static final Category SUBWAY = new Category("SUBWAY", "SW8", "지하철역", 1500);
    private static final Category MART = new Category("MART", "MT1", "대형마트", 1000);

    @Test
    @DisplayName("nearest JSON 은 추천(ScoreCalculator)이 기대하는 카카오 코드 기반 형식으로 만든다")
    void buildsScoreCalculatorCompatibleJson() {
        String json = HousingInfraPersistenceService.toNearestJson(List.of(
                new CollectedCategory(SUBWAY, 6, place("신설동역", 683))
        ));

        assertThat(json).isEqualTo("{\"SW8\":{\"name\":\"신설동역\",\"distanceM\":683}}");
    }

    @Test
    @DisplayName("0건 카테고리는 nearest JSON 에 넣지 않는다")
    void skipsCategoriesWithoutPlace() {
        String json = HousingInfraPersistenceService.toNearestJson(List.of(
                new CollectedCategory(SUBWAY, 6, place("신설동역", 683)),
                new CollectedCategory(MART, 0, null)
        ));

        assertThat(json).contains("SW8").doesNotContain("MT1");
    }

    private static NearestPlace place(String name, int distanceMeter) {
        return new NearestPlace("1", name, distanceMeter, null, null,
                BigDecimal.ONE, BigDecimal.ONE, null);
    }
}
