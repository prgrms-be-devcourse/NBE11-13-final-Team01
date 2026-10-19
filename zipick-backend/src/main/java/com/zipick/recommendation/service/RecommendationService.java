package com.zipick.recommendation.service;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.housing.domain.Candidate;
import com.zipick.housing.dto.HouseFilter;
import com.zipick.housing.infra.InfraDistanceProvider;
import com.zipick.housing.repository.CandidateQueryRepository;
import com.zipick.recommendation.config.RecommendationProperties;
import com.zipick.recommendation.domain.RecommendationProfile;
import com.zipick.recommendation.dto.RecommendationResponse;
import com.zipick.recommendation.dto.RecommendedHouseResponse;
import com.zipick.recommendation.dto.RelaxSuggestionResponse;
import com.zipick.recommendation.engine.HouseScore;
import com.zipick.recommendation.engine.RecommendationRanker;
import com.zipick.recommendation.engine.ScoreCalculator;
import com.zipick.recommendation.engine.UserContext;
import com.zipick.recommendation.repository.RecommendationProfileRepository;
import com.zipick.user.domain.UserPreference;
import com.zipick.user.domain.UserProfile;
import com.zipick.user.repository.UserPreferenceRepository;
import com.zipick.user.repository.UserProfileRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 추천 파이프라인:
 *   후보 조회(DB: 빼는 조건) → 지원조건 판정 → 항목 점수 → 가중합 → 주택별 대표 호실 → 두 묶음(순위 / 정보 부족)
 *
 * 후보는 공고 수집 테이블(notice ⨝ housing ⨝ housing_supply)에서 읽는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationService {

    private static final int CANDIDATE_LIMIT = 5000;
    private static final int PREVIEW_LIMIT = 200;

    private final RecommendationProperties props;
    private final CandidateQueryRepository candidateRepository;
    private final InfraDistanceProvider infraProvider;
    private final UserProfileRepository profileRepository;
    private final UserPreferenceRepository preferenceRepository;
    private final RecommendationProfileRepository recProfileRepository;
    private final ScoreCalculator calculator;
    private final RecommendationRanker ranker;

    /**
     * @param preview true면 기준 점수·최대 개수 없이 순위 묶음 후보 전체(최대 PREVIEW_LIMIT)를 돌려준다.
     *                프론트 슬라이더가 서버 없이 다시 순위를 매기는 데 쓴다.
     */
    public RecommendationResponse recommend(Long userId, HouseFilter filter, boolean preview) {
        Pipeline p = run(userId, filter);
        List<HouseScore> list = preview ? ranker.rank(p.housings(), 0, PREVIEW_LIMIT) : ranker.rank(p.housings());
        long total = p.housings().stream().filter(s -> !s.excluded() && s.complete() && s.score() >= props.minScore()).count();
        long excluded = p.housings().stream().filter(HouseScore::excluded).count();
        return new RecommendationResponse(
                p.profile() == null ? null : p.profile().getId(),
                p.profile() == null ? null : p.profile().getVersion(),
                props.baseWeights(),
                ordered(p.ctx().weights()),
                (int) total, p.housings().size(), (int) excluded,
                list.stream().map(this::toResponse).toList(),
                ranker.incomplete(p.housings(), props.maxResults()).stream().map(this::toResponse).toList());
    }

    /** 주택 하나의 점수와 근거 (상세 화면용). 대표 호실 기준 */
    public RecommendedHouseResponse scoreOne(Long userId, Long housingId) {
        HouseScore s = scoreHouses(userId, List.of(housingId)).get(housingId);
        if (s == null) throw new BusinessException(ErrorCode.HOUSE_NOT_FOUND);
        return toResponse(s);
    }

    /** 추천이 적을 때 조건 하나씩 풀어 보고 늘어나는 개수를 알려준다. */
    public RelaxSuggestionResponse relaxSuggestions(Long userId, HouseFilter filter) {
        Pipeline base = run(userId, filter);
        int current = ranker.rank(base.housings()).size();
        List<RelaxSuggestionResponse.Suggestion> out = new ArrayList<>();
        if (current >= props.minResultsBeforeRelax()) {
            return new RelaxSuggestionResponse(current, out);
        }
        UserPreference pref = base.ctx().preference();

        if (pref.getRentMax() != null || pref.getMonthlyBudget() != null) {
            int n = countWith(base, c -> c.withPreference(copy(pref, 5, 0)));
            if (n > current) out.add(new RelaxSuggestionResponse.Suggestion("budget", "월 예산을 5만 원 늘리기", n));
        }
        if (base.ctx().isMust(ScoreCalculator.REGION)) {
            Map<String, String> rules = new HashMap<>(base.ctx().rules());
            rules.put(ScoreCalculator.REGION, "PREFER");
            int n = countWith(base, c -> c.withRules(rules));
            if (n > current) out.add(new RelaxSuggestionResponse.Suggestion("regionRule",
                    "관심 지역을 '꼭'에서 '되면 좋음'으로", n));
        }
        if (pref.getAreaMin() != null || pref.getAreaMax() != null) {
            int n = countWith(base, c -> c.withPreference(copy(pref, 0, 5)));
            if (n > current) out.add(new RelaxSuggestionResponse.Suggestion("area", "희망 면적 범위를 위아래로 5㎡ 넓히기", n));
        }
        if (!base.ctx().preferredHousingTypes().isEmpty()) {
            int n = countWith(base, c -> c.withHousingTypes(Set.of()));
            if (n > current) out.add(new RelaxSuggestionResponse.Suggestion("housingTypes", "희망 주택 유형 조건 해제", n));
        }
        return new RelaxSuggestionResponse(current, out);
    }

    // ---- 우선순위 찾기(RecommendationTuningService)용 ----

    /** 특정 공고들만 대상으로 순위 묶음을 낸다 (새 공고 알림의 역매칭용). 숨긴 주택은 뺀다 */
    public List<HouseScore> rankForNotices(Long userId, Set<Long> noticeIds) {
        HouseFilter f = HouseFilter.openOnly().toBuilder().noticeIds(noticeIds).build();
        return ranker.rank(run(userId, f).housings());
    }

    /** 모집 중인 공고의 주택별 대표 호실 점수. 제외된 집도 포함한다. */
    public List<HouseScore> scoredCandidates(Long userId) {
        return run(userId, HouseFilter.openOnly()).housings();
    }

    /** 주택 여러 곳의 대표 호실 점수. 마감·숨김 여부와 관계없이 계산한다. */
    public Map<Long, HouseScore> scoreHouses(Long userId, Collection<Long> housingIds) {
        if (housingIds.isEmpty()) return Map.of();
        UserContext ctx = buildContext(userId);
        List<Candidate> candidates = candidateRepository.findByHousingIds(housingIds);
        return ranker.bestPerHousing(scoreAll(ctx, candidates)).stream()
                .collect(Collectors.toMap(HouseScore::housingId, Function.identity()));
    }

    public RecommendedHouseResponse toResponse(HouseScore s) {
        return RecommendedHouseResponse.from(s, calculator.monthlyCost(s.candidate()));
    }

    /** 활성 추천 기준의 우선순위 (없으면 전부 3) */
    public Map<String, Integer> currentPriorities(Long userId) {
        return ordered(buildContext(userId).weights());
    }

    // ---- 내부 ----

    private record Pipeline(UserContext ctx, RecommendationProfile profile, List<Candidate> candidates,
                            List<HouseScore> housings) {}

    private Pipeline run(Long userId, HouseFilter filter) {
        UserContext ctx = buildContext(userId);
        RecommendationProfile profile = recProfileRepository.findByUserIdAndActiveTrue(userId).orElse(null);
        // 숨긴 주택은 DB에서 NOT EXISTS 로 뺀다
        List<Candidate> candidates = candidateRepository.find(filter, userId, CANDIDATE_LIMIT);
        List<HouseScore> housings = ranker.bestPerHousing(scoreAll(ctx, candidates));
        return new Pipeline(ctx, profile, candidates, housings);
    }

    private List<HouseScore> scoreAll(UserContext ctx, List<Candidate> candidates) {
        Map<Long, Map<String, Integer>> nearest = infraProvider.nearest(candidates);
        return candidates.stream().map(c -> calculator.score(ctx, c, nearest.get(c.housingId()))).toList();
    }

    private int countWith(Pipeline base, Function<UserContext, UserContext> change) {
        UserContext ctx = change.apply(base.ctx());
        return ranker.rank(ranker.bestPerHousing(scoreAll(ctx, base.candidates()))).size();
    }

    private UserContext buildContext(Long userId) {
        UserProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROFILE_NOT_FOUND));
        UserPreference pref = preferenceRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PREFERENCE_NOT_FOUND));
        RecommendationProfile rp = recProfileRepository.findByUserIdAndActiveTrue(userId).orElse(null);
        return UserContext.of(profile, pref, rp, props.baseWeights(), props.defaultMustItems());
    }

    private Map<String, Integer> ordered(Map<String, Integer> weights) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String item : ScoreCalculator.ITEMS) out.put(item, weights.getOrDefault(item, 3));
        return out;
    }

    /** 완화 시나리오용 희망 조건 복사본 (DB에 저장하지 않는다) */
    private UserPreference copy(UserPreference p, int budgetDelta, int areaDelta) {
        BigDecimal d = BigDecimal.valueOf(areaDelta);
        return UserPreference.builder()
                .userId(p.getUserId()).regions(p.getRegions())
                .depositMax(p.getDepositMax())
                .rentMax(p.getRentMax() == null ? null : p.getRentMax() + budgetDelta)
                .areaMin(p.getAreaMin() == null ? null : p.getAreaMin().subtract(d).max(BigDecimal.ZERO))
                .areaMax(p.getAreaMax() == null ? null : p.getAreaMax().add(d))
                .housingTypes(p.getHousingTypes()).rentalTypes(p.getRentalTypes())
                .monthlyBudget(p.getMonthlyBudget() == null ? null : p.getMonthlyBudget() + budgetDelta)
                .roomsMin(p.getRoomsMin())
                .build();
    }
}
