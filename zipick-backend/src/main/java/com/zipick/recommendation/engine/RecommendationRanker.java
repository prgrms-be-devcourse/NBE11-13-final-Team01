package com.zipick.recommendation.engine;

import com.zipick.recommendation.config.RecommendationProperties;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 4단계: 순위 정리.
 *
 * 1. 같은 주택의 호실 중 가장 좋은 호실 하나를 대표로 남긴다 (bestPerHousing)
 * 2. 순위 묶음(rank): 제외되지 않고, 필수 정보가 다 있고, 기준 점수 이상인 집을 점수순(동점이면 마감 임박순)으로,
 *    공고당 최대 N곳, 전체 최대 M곳
 * 3. 정보 부족 묶음(incomplete): 제외되지 않았지만 필수 정보가 없는 집을 마감 임박순으로
 */
@Component
@RequiredArgsConstructor
public class RecommendationRanker {

    private static final Comparator<HouseScore> BY_SCORE = Comparator.comparingInt(HouseScore::score).reversed();
    private static final Comparator<HouseScore> BY_CLOSE = Comparator.comparing(
            (HouseScore s) -> s.candidate().closeDate(), Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()));
    private static final Comparator<HouseScore> BY_ID = Comparator.comparing(HouseScore::housingId);

    private final RecommendationProperties props;

    /** 주택마다 대표 호실 하나: 제외되지 않은 것 > 필수 정보가 있는 것 > 점수 높은 것 > 호실 id 작은 것 */
    public List<HouseScore> bestPerHousing(List<HouseScore> scored) {
        Comparator<HouseScore> better = Comparator.<HouseScore, Boolean>comparing(s -> !s.excluded())
                .thenComparing(HouseScore::complete)
                .thenComparingInt(HouseScore::score)
                .thenComparing(s -> -s.candidate().supplyId());
        Map<Long, HouseScore> best = new LinkedHashMap<>();
        for (HouseScore s : scored) {
            best.merge(s.housingId(), s, (a, b) -> better.compare(a, b) >= 0 ? a : b);
        }
        return new ArrayList<>(best.values());
    }

    public List<HouseScore> rank(List<HouseScore> housings) {
        return rank(housings, props.minScore(), props.maxResults());
    }

    public List<HouseScore> rank(List<HouseScore> housings, int minScore, int maxResults) {
        List<HouseScore> sorted = housings.stream()
                .filter(s -> !s.excluded() && s.complete())
                .filter(s -> s.score() >= minScore)
                .sorted(BY_SCORE.thenComparing(BY_CLOSE).thenComparing(BY_ID))
                .toList();

        Map<Long, Integer> perNotice = new HashMap<>();
        List<HouseScore> out = new ArrayList<>();
        for (HouseScore s : sorted) {
            if (out.size() >= maxResults) break;
            Long noticeId = s.candidate().noticeId();
            int n = perNotice.getOrDefault(noticeId, 0);
            if (n >= props.maxPerAnnouncement()) continue;
            perNotice.put(noticeId, n + 1);
            out.add(s);
        }
        return out;
    }

    /** 정보 부족 묶음: 점수로 비교할 수 없으니 관심 지역 순위(지역 점수 높은 순) → 마감 임박순 */
    public List<HouseScore> incomplete(List<HouseScore> housings, int maxResults) {
        Comparator<HouseScore> byRegion = Comparator.comparingDouble((HouseScore s) -> {
            ItemScore r = s.itemScores().get(ScoreCalculator.REGION);
            return r != null && r.available() ? r.score() : 0;
        }).reversed();
        return housings.stream()
                .filter(s -> !s.excluded() && !s.complete())
                .sorted(byRegion.thenComparing(BY_CLOSE).thenComparing(BY_ID))
                .limit(maxResults)
                .toList();
    }
}
