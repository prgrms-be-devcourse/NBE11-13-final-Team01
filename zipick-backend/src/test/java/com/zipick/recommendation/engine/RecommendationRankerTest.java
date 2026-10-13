package com.zipick.recommendation.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.zipick.housing.domain.Announcement;
import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import com.zipick.housing.domain.SupplyHouse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RecommendationRankerTest {

    private final RecommendationRanker ranker = new RecommendationRanker(TestFixtures.props());

    private HouseScore scored(long houseId, Announcement ann, int score) {
        SupplyHouse h = SupplyHouse.builder().announcement(ann).address("a").approximate(false)
                .housingType(HousingType.APARTMENT).build();
        ReflectionTestUtils.setField(h, "id", houseId);
        return new HouseScore(h, score, Map.of(), Map.of(),
                new EligibilityResult(EligibilityResult.Status.PASS, List.of()), List.of(), null);
    }

    @Test
    @DisplayName("60점 미만은 빼고, 점수 내림차순, 공고당 최대 5곳")
    void rank() {
        Announcement a = TestFixtures.announcement(RentalType.PURCHASE);
        ReflectionTestUtils.setField(a, "id", 1L);
        Announcement b = TestFixtures.announcement(RentalType.HAPPY);
        ReflectionTestUtils.setField(b, "id", 2L);

        List<HouseScore> in = new ArrayList<>();
        for (int i = 0; i < 8; i++) in.add(scored(i + 1, a, 90 - i));   // 공고 a: 90..83
        in.add(scored(100, b, 95));
        in.add(scored(101, b, 50));                                     // 기준 미만

        List<HouseScore> out = ranker.rank(in);

        assertThat(out).hasSize(6); // b 1곳 + a 5곳
        assertThat(out.get(0).house().getId()).isEqualTo(100L);
        assertThat(out.stream().filter(s -> s.house().getAnnouncement() == a)).hasSize(5);
        assertThat(out).noneMatch(s -> s.score() < 60);
    }

    @Test
    @DisplayName("제외된 집은 점수가 높아도 나오지 않는다")
    void excluded() {
        Announcement a = TestFixtures.announcement(RentalType.PURCHASE);
        ReflectionTestUtils.setField(a, "id", 1L);
        HouseScore fail = new HouseScore(scored(1, a, 99).house(), 99, Map.of(), Map.of(),
                new EligibilityResult(EligibilityResult.Status.FAIL, List.of()), List.of(), null);
        assertThat(ranker.rank(List.of(fail))).isEmpty();
    }
}
