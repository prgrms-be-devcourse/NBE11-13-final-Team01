package com.zipick.recommendation.service;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.recommendation.config.RecommendationProperties;
import com.zipick.recommendation.domain.PairwiseChoice;
import com.zipick.recommendation.dto.AhpRequest;
import com.zipick.recommendation.dto.AhpResponse;
import com.zipick.recommendation.dto.PairChoiceRequest;
import com.zipick.recommendation.dto.PairLearnResponse;
import com.zipick.recommendation.dto.PairQuestionResponse;
import com.zipick.recommendation.dto.RecommendationProfileResponse;
import com.zipick.recommendation.engine.AhpCalculator;
import com.zipick.recommendation.engine.HouseScore;
import com.zipick.recommendation.engine.PairwiseLearner;
import com.zipick.recommendation.engine.PriorityConverter;
import com.zipick.recommendation.engine.ScoreCalculator;
import com.zipick.recommendation.repository.PairwiseChoiceRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 추천 우선순위를 사용자에게 맞추는 두 가지 방법.
 *
 * 1) AHP: 온보딩에서 "A와 B 중 무엇이 더 중요해요?" 질문 3개 → 우선순위
 * 2) 쌍대 선택 학습: 실제 집 두 곳을 보여 주고 고르게 한 기록 → Bradley-Terry 로 우선순위 학습
 *
 * 둘 다 결과는 활성 추천 기준의 weights(1~5)로 저장되므로 추천 엔진은 바뀌지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationTuningService {

    /** 이만큼 골라야 학습 결과를 적용할 수 있다 */
    public static final int MIN_PAIR_ANSWERS = 5;
    /** 다음 질문을 고를 때 볼 상위 후보 수 (쌍 개수가 n²으로 늘어서 제한) */
    private static final int PAIR_POOL = 40;

    private final RecommendationService recommendationService;
    private final RecommendationProfileService profileService;
    private final PairwiseChoiceRepository choiceRepository;
    private final AhpCalculator ahpCalculator;
    private final PairwiseLearner learner;
    private final RecommendationProperties props;

    // ---- AHP ----

    @Transactional
    public AhpResponse ahp(Long userId, AhpRequest request) {
        AhpCalculator.Result result = ahpCalculator.calculate(request.comparisons().stream()
                .map(c -> new AhpCalculator.Comparison(c.left(), c.right(), c.value()))
                .toList());

        // AHP 가중치 비율을 그대로 우선순위 비율로 쓴다. 전부 "비슷해요"면 모두 3(기본값).
        // 비교하지 않은 항목은 지금 값을 유지한다.
        Map<String, Integer> priorities = new LinkedHashMap<>(recommendationService.currentPriorities(userId));
        priorities.putAll(PriorityConverter.fromRatios(result.weights()));

        RecommendationProfileResponse profile = request.apply() ? profileService.applyWeights(userId, priorities) : null;
        return new AhpResponse(result.weights(), priorities, result.consistencyRatio(), result.consistent(), profile);
    }

    // ---- 쌍대 선택 학습 ----

    public PairQuestionResponse nextPair(Long userId) {
        List<PairwiseChoice> choices = choiceRepository.findAllByUserIdOrderByIdAsc(userId);
        PairwiseLearner.Result learned = learn(userId, choices);
        Set<String> asked = choices.stream()
                .map(c -> PairwiseLearner.pairKey(c.getLeftHouseId(), c.getRightHouseId()))
                .collect(Collectors.toSet());

        Map<Long, Integer> shown = new HashMap<>();
        choices.forEach(c -> {
            shown.merge(c.getLeftHouseId(), 1, Integer::sum);
            shown.merge(c.getRightHouseId(), 1, Integer::sum);
        });

        List<HouseScore> candidates = recommendationService.scoredCandidates(userId);
        return learner.nextPair(candidates, asked, shown, learned.beta(), PAIR_POOL)
                .map(pair -> new PairQuestionResponse(
                        recommendationService.toResponse(pair[0]), recommendationService.toResponse(pair[1]),
                        choices.size(), MIN_PAIR_ANSWERS))
                .orElseGet(() -> new PairQuestionResponse(null, null, choices.size(), MIN_PAIR_ANSWERS));
    }

    @Transactional
    public PairLearnResponse choose(Long userId, PairChoiceRequest request) {
        if (request.leftHouseId().equals(request.rightHouseId())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "서로 다른 두 집을 골라야 합니다.");
        }
        Map<Long, HouseScore> found = recommendationService.scoreHouses(userId,
                List.of(request.leftHouseId(), request.rightHouseId()));
        if (found.size() < 2) throw new BusinessException(ErrorCode.HOUSE_NOT_FOUND);

        // 같은 쌍에 다시 답하면 이전 답을 지운다
        String key = PairwiseLearner.pairKey(request.leftHouseId(), request.rightHouseId());
        choiceRepository.findAllByUserIdOrderByIdAsc(userId).stream()
                .filter(c -> PairwiseLearner.pairKey(c.getLeftHouseId(), c.getRightHouseId()).equals(key))
                .forEach(choiceRepository::delete);

        choiceRepository.save(PairwiseChoice.builder()
                .userId(userId)
                .leftHouseId(request.leftHouseId())
                .rightHouseId(request.rightHouseId())
                .choice(request.choice())
                .build());
        return result(userId);
    }

    public PairLearnResponse result(Long userId) {
        List<PairwiseChoice> choices = choiceRepository.findAllByUserIdOrderByIdAsc(userId);
        PairwiseLearner.Result learned = learn(userId, choices);
        return new PairLearnResponse(
                choices.size(), MIN_PAIR_ANSWERS, choices.size() >= MIN_PAIR_ANSWERS,
                recommendationService.currentPriorities(userId),
                PriorityConverter.fromWeights(learned.weights(), props.baseWeights()),
                learned.weights(), learned.agreement());
    }

    @Transactional
    public RecommendationProfileResponse applyLearned(Long userId) {
        PairLearnResponse r = result(userId);
        if (!r.ready()) {
            throw new BusinessException(ErrorCode.PAIR_ANSWERS_NOT_ENOUGH,
                    MIN_PAIR_ANSWERS + "번 이상 골라야 적용할 수 있어요. (지금 " + r.answered() + "번)");
        }
        return profileService.applyWeights(userId, r.learnedPriorities());
    }

    @Transactional
    public void reset(Long userId) {
        choiceRepository.deleteAllByUserId(userId);
    }

    /**
     * 저장된 선택으로 학습한다. 사전 분포는 항상 기본 가중치(우선순위 전부 3)라서
     * 같은 답이면 몇 번을 적용해도 결과가 같다.
     * 항목 점수는 지금의 희망 조건으로 다시 계산한다(조건이 바뀌면 학습도 따라간다).
     */
    private PairwiseLearner.Result learn(Long userId, List<PairwiseChoice> choices) {
        Set<Long> ids = new HashSet<>();
        choices.forEach(c -> {
            ids.add(c.getLeftHouseId());
            ids.add(c.getRightHouseId());
        });
        Map<Long, HouseScore> scores = recommendationService.scoreHouses(userId, ids);

        List<PairwiseLearner.Observation> obs = new ArrayList<>();
        for (PairwiseChoice c : choices) {
            HouseScore l = scores.get(c.getLeftHouseId());
            HouseScore r = scores.get(c.getRightHouseId());
            if (l == null || r == null) continue;   // 집이 삭제된 경우
            obs.add(new PairwiseLearner.Observation(PairwiseLearner.diff(l, r), c.target()));
        }
        Map<String, Integer> neutral = ScoreCalculator.ITEMS.stream()
                .collect(Collectors.toMap(k -> k, k -> PriorityConverter.NEUTRAL));
        return learner.learn(obs, PriorityConverter.toWeights(neutral, props.baseWeights()));
    }
}
