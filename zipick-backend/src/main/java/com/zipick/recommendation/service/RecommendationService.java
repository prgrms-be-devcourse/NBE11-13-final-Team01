package com.zipick.recommendation.service;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.housing.domain.InfraSummary;
import com.zipick.housing.domain.SupplyHouse;
import com.zipick.housing.dto.HouseFilter;
import com.zipick.housing.repository.InfraSummaryRepository;
import com.zipick.housing.repository.SupplyHouseRepository;
import com.zipick.recommendation.config.RecommendationProperties;
import com.zipick.recommendation.domain.RecommendationFeedback;
import com.zipick.recommendation.domain.RecommendationProfile;
import com.zipick.recommendation.dto.RecommendationResponse;
import com.zipick.recommendation.dto.RecommendedHouseResponse;
import com.zipick.recommendation.dto.RelaxSuggestionResponse;
import com.zipick.recommendation.engine.HouseScore;
import com.zipick.recommendation.engine.RecommendationRanker;
import com.zipick.recommendation.engine.ScoreCalculator;
import com.zipick.recommendation.engine.UserContext;
import com.zipick.recommendation.repository.RecommendationFeedbackRepository;
import com.zipick.recommendation.repository.RecommendationProfileRepository;
import com.zipick.user.domain.UserPreference;
import com.zipick.user.domain.UserProfile;
import com.zipick.user.repository.UserPreferenceRepository;
import com.zipick.user.repository.UserProfileRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
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
 *   필터로 후보 조회 → 숨긴 집 제외 → 지원조건 판정 → 항목 점수 → 가중합 → 순위 정리
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationService {

    private static final int CANDIDATE_LIMIT = 2000;
    private static final int PREVIEW_LIMIT = 200;

    private final RecommendationProperties props;
    private final SupplyHouseRepository houseRepository;
    private final InfraSummaryRepository infraRepository;
    private final UserProfileRepository profileRepository;
    private final UserPreferenceRepository preferenceRepository;
    private final RecommendationProfileRepository recProfileRepository;
    private final RecommendationFeedbackRepository feedbackRepository;
    private final ScoreCalculator calculator;
    private final RecommendationRanker ranker;

    public RecommendationResponse recommend(Long userId, HouseFilter filter) {
        return recommend(userId, filter, false);
    }

    /**
     * @param preview true면 기준 점수·최대 개수 없이 후보 전체(최대 PREVIEW_LIMIT)를 돌려준다.
     */
    public RecommendationResponse recommend(Long userId, HouseFilter filter, boolean preview) {
        Pipeline p = run(userId, filter);
        List<HouseScore> list = preview ? ranker.rank(p.scored(), 0, PREVIEW_LIMIT) : p.ranked();
        List<RecommendedHouseResponse> houses = list.stream()
                .map(s -> RecommendedHouseResponse.from(s, calculator.monthlyCost(s.house())))
                .toList();
        long total = p.scored().stream().filter(s -> !s.excluded() && s.score() >= props.minScore()).count();
        long excluded = p.scored().stream().filter(HouseScore::excluded).count();
        return new RecommendationResponse(
                p.profile() == null ? null : p.profile().getId(),
                p.profile() == null ? null : p.profile().getVersion(),
                props.baseWeights(),
                p.ctx().weights(),
                (int) total, p.scored().size(), (int) excluded, houses);
    }

    /** 집 하나의 점수와 근거 (상세 화면용) */
    public RecommendedHouseResponse scoreOne(Long userId, Long houseId) {
        SupplyHouse house = houseRepository.findById(houseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.HOUSE_NOT_FOUND));
        UserContext ctx = buildContext(userId);
        InfraSummary infra = infraRepository.findBySupplyHouseId(houseId).orElse(null);
        HouseScore s = calculator.score(ctx, house, infra);
        return RecommendedHouseResponse.from(s, calculator.monthlyCost(house));
    }

    /** 추천이 적을 때 조건 하나씩 풀어 보고 늘어나는 개수를 알려준다. */
    public RelaxSuggestionResponse relaxSuggestions(Long userId, HouseFilter filter) {
        Pipeline base = run(userId, filter);
        int current = base.ranked().size();
        List<RelaxSuggestionResponse.Suggestion> out = new ArrayList<>();
        if (current >= props.minResultsBeforeRelax()) {
            return new RelaxSuggestionResponse(current, out);
        }

        UserPreference pref = base.ctx().preference();

        // 1) 월 임대료 상한 +5만 원 (예산 재계산)
        if (pref.getRentMax() != null) {
            int n = countWith(base, c -> withBudgetDelta(c, 5));
            if (n > current) out.add(new RelaxSuggestionResponse.Suggestion("rentMax",
                    "월 임대료 상한 " + pref.getRentMax() + "만 원 → " + (pref.getRentMax() + 5) + "만 원", n));
        }
        // 2) 관심 지역 '꼭' 해제
        if (base.ctx().isMust("region")) {
            int n = countWith(base, c -> withoutMust(c, "region"));
            if (n > current) out.add(new RelaxSuggestionResponse.Suggestion("regionRule",
                    "관심 지역을 '꼭'에서 '되면 좋음'으로", n));
        }
        // 3) 면적 범위 ±5㎡
        if (pref.getAreaMin() != null || pref.getAreaMax() != null) {
            int n = countWith(base, c -> withAreaDelta(c, 5));
            if (n > current) out.add(new RelaxSuggestionResponse.Suggestion("area",
                    "희망 면적 범위를 위아래로 5㎡ 넓히기", n));
        }
        // 4) 희망 주택 유형 해제
        if (!base.ctx().preferredHousingTypes().isEmpty()) {
            int n = countWith(base, c -> withoutHousingTypes(c));
            if (n > current) out.add(new RelaxSuggestionResponse.Suggestion("housingTypes",
                    "희망 주택 유형 조건 해제", n));
        }
        return new RelaxSuggestionResponse(current, out);
    }

    // ---- 내부 ----

    private record Pipeline(UserContext ctx, RecommendationProfile profile, List<SupplyHouse> candidates,
                            Map<Long, InfraSummary> infraById, List<HouseScore> scored, List<HouseScore> ranked) {}

    private Pipeline run(Long userId, HouseFilter filter) {
        UserContext ctx = buildContext(userId);
        RecommendationProfile profile = recProfileRepository.findByUserIdAndActiveTrue(userId).orElse(null);

        Set<Long> hidden = feedbackRepository.findAllByUserId(userId).stream()
                .map(RecommendationFeedback::getSupplyHouseId).collect(Collectors.toSet());

        List<SupplyHouse> candidates = houseRepository.findByFilter(filter, CANDIDATE_LIMIT).stream()
                .filter(h -> !hidden.contains(h.getId()))
                .toList();

        Map<Long, InfraSummary> infraById = infraRepository.findAllBySupplyHouseIdIn(
                        candidates.stream().map(SupplyHouse::getId).toList())
                .stream().collect(Collectors.toMap(i -> i.getSupplyHouse().getId(), Function.identity()));

        List<HouseScore> scored = scoreAll(ctx, candidates, infraById);
        return new Pipeline(ctx, profile, candidates, infraById, scored, ranker.rank(scored));
    }

    private List<HouseScore> scoreAll(UserContext ctx, List<SupplyHouse> candidates, Map<Long, InfraSummary> infra) {
        return candidates.stream().map(h -> calculator.score(ctx, h, infra.get(h.getId()))).toList();
    }

    private int countWith(Pipeline base, Function<UserContext, UserContext> change) {
        UserContext ctx = change.apply(base.ctx());
        return ranker.rank(scoreAll(ctx, base.candidates(), base.infraById())).size();
    }

    private UserContext buildContext(Long userId) {
        UserProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROFILE_NOT_FOUND));
        UserPreference pref = preferenceRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PREFERENCE_NOT_FOUND));
        RecommendationProfile rp = recProfileRepository.findByUserIdAndActiveTrue(userId).orElse(null);
        return UserContext.of(profile, pref, rp, props.baseWeights());
    }

    // ---- 완화 시나리오용 컨텍스트 변형 (DB에 저장하지 않는다) ----

    private UserContext withBudgetDelta(UserContext c, int deltaRent) {
        UserPreference p = c.preference();
        UserPreference copy = UserPreference.builder()
                .userId(p.getUserId()).regions(p.getRegions())
                .depositMin(p.getDepositMin()).depositMax(p.getDepositMax())
                .rentMin(p.getRentMin()).rentMax(p.getRentMax() == null ? null : p.getRentMax() + deltaRent)
                .areaMin(p.getAreaMin()).areaMax(p.getAreaMax())
                .housingTypes(p.getHousingTypes()).rentalTypes(p.getRentalTypes())
                .monthlyBudget(p.getMonthlyBudget() == null ? null : p.getMonthlyBudget() + deltaRent)
                .build();
        return new UserContext(c.profile(), copy, c.regionRanks(), c.preferredHousingTypes(), c.weights(),
                c.rules(), c.infraWeights(), c.today());
    }

    private UserContext withAreaDelta(UserContext c, int delta) {
        UserPreference p = c.preference();
        BigDecimal d = BigDecimal.valueOf(delta);
        UserPreference copy = UserPreference.builder()
                .userId(p.getUserId()).regions(p.getRegions())
                .depositMin(p.getDepositMin()).depositMax(p.getDepositMax())
                .rentMin(p.getRentMin()).rentMax(p.getRentMax())
                .areaMin(p.getAreaMin() == null ? null : p.getAreaMin().subtract(d).max(BigDecimal.ZERO))
                .areaMax(p.getAreaMax() == null ? null : p.getAreaMax().add(d))
                .housingTypes(p.getHousingTypes()).rentalTypes(p.getRentalTypes())
                .monthlyBudget(p.getMonthlyBudget())
                .build();
        return new UserContext(c.profile(), copy, c.regionRanks(), c.preferredHousingTypes(), c.weights(),
                c.rules(), c.infraWeights(), c.today());
    }

    private UserContext withoutMust(UserContext c, String item) {
        Map<String, String> rules = new HashMap<>(c.rules());
        rules.put(item, "PREFER");
        return new UserContext(c.profile(), c.preference(), c.regionRanks(), c.preferredHousingTypes(),
                c.weights(), rules, c.infraWeights(), c.today());
    }

    private UserContext withoutHousingTypes(UserContext c) {
        return new UserContext(c.profile(), c.preference(), c.regionRanks(), Set.of(), c.weights(),
                c.rules(), c.infraWeights(), c.today());
    }
}
