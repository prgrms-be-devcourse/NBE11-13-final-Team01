package com.zipick.recommendation.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.zipick.housing.domain.Candidate;
import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecommendationRankerTest {

    private final RecommendationRanker ranker = new RecommendationRanker(TestFixtures.props());

    private HouseScore scored(long supplyId, long housingId, long noticeId, int score, boolean complete,
                              EligibilityResult.Status status) {
        Candidate base = TestFixtures.candidate(supplyId, noticeId, RentalType.YOUTH_PURCHASE, "마포구", HousingType.APARTMENT,
                "29", 1500, 30, 1, null);
        Candidate c = new Candidate(supplyId, housingId, noticeId, base.noticeTitle(), base.noticeType(), base.rentalType(),
                base.status(), base.closeDate(), null, "LH", "LH", "주택", "주소", "서울특별시", "마포구", null, null, null,
                "아파트", HousingType.APARTMENT, base.exclusiveArea(), 1, 1, 1500, 30, 1);
        return new HouseScore(c, score, Map.of(), Map.of(), new EligibilityResult(status, List.of()), List.of(), null,
                complete);
    }

    private HouseScore scored(long id, long noticeId, int score) {
        return scored(id, id, noticeId, score, true, EligibilityResult.Status.PASS);
    }

    @Test
    @DisplayName("60점 미만은 빼고, 점수 내림차순, 공고당 최대 5곳")
    void rank() {
        List<HouseScore> in = new ArrayList<>();
        for (int i = 0; i < 8; i++) in.add(scored(i + 1, 1, 90 - i));   // 공고 1: 90..83
        in.add(scored(100, 2, 95));
        in.add(scored(101, 2, 50));                                      // 기준 미만

        List<HouseScore> out = ranker.rank(in);

        assertThat(out).hasSize(6); // 공고 2의 1곳 + 공고 1의 5곳
        assertThat(out.get(0).housingId()).isEqualTo(100L);
        assertThat(out.stream().filter(s -> s.candidate().noticeId() == 1L)).hasSize(5);
        assertThat(out).noneMatch(s -> s.score() < 60);
    }

    @Test
    @DisplayName("제외된 집은 점수가 높아도 나오지 않는다")
    void excluded() {
        assertThat(ranker.rank(List.of(scored(1, 1, 1, 99, true, EligibilityResult.Status.FAIL)))).isEmpty();
    }

    @Test
    @DisplayName("같은 주택의 호실 중 점수가 가장 높은 호실 하나만 대표로 남긴다")
    void bestPerHousing() {
        List<HouseScore> best = ranker.bestPerHousing(List.of(
                scored(1, 10, 1, 70, true, EligibilityResult.Status.PASS),
                scored(2, 10, 1, 90, true, EligibilityResult.Status.PASS),
                scored(3, 11, 1, 80, true, EligibilityResult.Status.PASS)));
        assertThat(best).hasSize(2);
        assertThat(best).anyMatch(s -> s.housingId() == 10L && s.candidate().supplyId() == 2L);
    }

    @Test
    @DisplayName("필수 정보가 없는 집은 순위에서 빠지고 '정보 부족' 묶음에 마감 임박순으로 들어간다")
    void incompleteBundle() {
        HouseScore complete = scored(1, 1, 1, 90, true, EligibilityResult.Status.PASS);
        HouseScore noCost = scored(2, 2, 1, 95, false, EligibilityResult.Status.PASS);
        HouseScore noCostButFail = scored(3, 3, 1, 95, false, EligibilityResult.Status.FAIL);

        assertThat(ranker.rank(List.of(complete, noCost, noCostButFail))).containsExactly(complete);
        assertThat(ranker.incomplete(List.of(complete, noCost, noCostButFail), 30)).containsExactly(noCost);
    }
}
