package com.zipick.recommendation.engine;

import com.zipick.recommendation.config.RecommendationProperties;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 4단계: 순위 정리.
 * 제외된 집을 빼고, 기준 점수 이상만 남기고, 점수 내림차순(동점이면 마감 임박순)으로 정렬한 뒤
 * 공고당 최대 N곳, 전체 최대 M곳으로 자른다.
 */
@Component
@RequiredArgsConstructor
public class RecommendationRanker {

    private final RecommendationProperties props;

    public List<HouseScore> rank(List<HouseScore> scored) {
        return rank(scored, props.minScore(), props.maxResults());
    }

    public List<HouseScore> rank(List<HouseScore> scored, int minScore, int maxResults) {
        Comparator<HouseScore> byScore = Comparator.comparingInt(HouseScore::score).reversed();
        Comparator<HouseScore> byClose = Comparator.comparing(
                (HouseScore s) -> s.house().getAnnouncement().getCloseDate(),
                Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()));
        Comparator<HouseScore> byId = Comparator.comparing(s -> s.house().getId());

        List<HouseScore> sorted = scored.stream()
                .filter(s -> !s.excluded())
                .filter(s -> s.score() >= minScore)
                .sorted(byScore.thenComparing(byClose).thenComparing(byId))
                .toList();

        Map<Long, Integer> perAnnouncement = new HashMap<>();
        List<HouseScore> out = new ArrayList<>();
        for (HouseScore s : sorted) {
            if (out.size() >= maxResults) break;
            Long annId = s.house().getAnnouncement().getId();
            int n = perAnnouncement.getOrDefault(annId, 0);
            if (n >= props.maxPerAnnouncement()) continue;
            perAnnouncement.put(annId, n + 1);
            out.add(s);
        }
        return out;
    }
}
