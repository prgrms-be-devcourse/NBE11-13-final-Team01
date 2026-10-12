package com.zipick.recommendation.service;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.global.util.JsonUtil;
import com.zipick.recommendation.domain.RecommendationProfile;
import com.zipick.recommendation.dto.RecommendationProfileRequest;
import com.zipick.recommendation.dto.RecommendationProfileResponse;
import com.zipick.recommendation.engine.ScoreCalculator;
import com.zipick.recommendation.repository.RecommendationProfileRepository;
import com.zipick.user.service.UserConditionChangedEvent;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationProfileService {

    private final RecommendationProfileRepository repository;

    public List<RecommendationProfileResponse> list(Long userId) {
        return repository.findAllByUserIdOrderByIdAsc(userId).stream()
                .map(RecommendationProfileResponse::from).toList();
    }

    public Optional<RecommendationProfile> findActive(Long userId) {
        return repository.findByUserIdAndActiveTrue(userId);
    }

    @Transactional
    public RecommendationProfileResponse create(Long userId, RecommendationProfileRequest r) {
        if (repository.countByUserId(userId) >= RecommendationProfile.MAX_PER_USER) {
            throw new BusinessException(ErrorCode.PROFILE_LIMIT_EXCEEDED);
        }
        validate(r);
        boolean first = repository.countByUserId(userId) == 0;
        RecommendationProfile p = repository.save(RecommendationProfile.builder()
                .userId(userId)
                .name(r.name() == null ? "기본" : r.name())
                .active(first)
                .weights(JsonUtil.write(r.weights() == null ? defaultWeights() : r.weights()))
                .rules(JsonUtil.write(r.rules() == null ? Map.of() : r.rules()))
                .infraWeights(JsonUtil.write(r.infraWeights() == null ? Map.of() : r.infraWeights()))
                .build());
        return RecommendationProfileResponse.from(p);
    }

    @Transactional
    public RecommendationProfileResponse update(Long userId, Long id, RecommendationProfileRequest r) {
        validate(r);
        RecommendationProfile p = get(userId, id);
        p.update(r.name(),
                r.weights() == null ? null : JsonUtil.write(r.weights()),
                r.rules() == null ? null : JsonUtil.write(r.rules()),
                r.infraWeights() == null ? null : JsonUtil.write(r.infraWeights()));
        return RecommendationProfileResponse.from(p);
    }

    @Transactional
    public RecommendationProfileResponse activate(Long userId, Long id) {
        RecommendationProfile target = get(userId, id);
        repository.findAllByUserIdOrderByIdAsc(userId).forEach(RecommendationProfile::deactivate);
        target.activate();
        return RecommendationProfileResponse.from(target);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        RecommendationProfile p = get(userId, id);
        if (p.isActive() && repository.countByUserId(userId) > 1) {
            throw new BusinessException(ErrorCode.ACTIVE_PROFILE_REQUIRED);
        }
        repository.delete(p);
    }

    /** 기본 정보·희망 조건이 바뀌면 활성 기준의 버전을 올린다 (캐시 키 무효화). */
    @EventListener
    @Transactional
    public void onUserConditionChanged(UserConditionChangedEvent e) {
        repository.findByUserIdAndActiveTrue(e.userId()).ifPresent(RecommendationProfile::bumpVersion);
    }

    private RecommendationProfile get(Long userId, Long id) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RECOMMENDATION_PROFILE_NOT_FOUND));
    }

    private void validate(RecommendationProfileRequest r) {
        if (r.weights() != null) {
            for (var e : r.weights().entrySet()) {
                if (!ScoreCalculator.ITEMS.contains(e.getKey()))
                    throw new BusinessException(ErrorCode.INVALID_INPUT, "알 수 없는 항목: " + e.getKey());
                if (e.getValue() == null || e.getValue() < 1 || e.getValue() > 5)
                    throw new BusinessException(ErrorCode.INVALID_INPUT, "우선순위는 1~5 사이여야 합니다: " + e.getKey());
            }
        }
        if (r.rules() != null) {
            for (var e : r.rules().entrySet()) {
                if (!ScoreCalculator.ITEMS.contains(e.getKey()))
                    throw new BusinessException(ErrorCode.INVALID_INPUT, "알 수 없는 항목: " + e.getKey());
                String v = e.getValue() == null ? "" : e.getValue().toUpperCase();
                if (!v.equals("MUST") && !v.equals("PREFER"))
                    throw new BusinessException(ErrorCode.INVALID_INPUT, "규칙은 MUST 또는 PREFER여야 합니다: " + e.getKey());
            }
        }
        if (r.infraWeights() != null) {
            for (var e : r.infraWeights().entrySet()) {
                if (e.getValue() == null || e.getValue() < 1 || e.getValue() > 5)
                    throw new BusinessException(ErrorCode.INVALID_INPUT, "인프라 가중치는 1~5 사이여야 합니다: " + e.getKey());
            }
        }
    }

    private Map<String, Integer> defaultWeights() {
        return Map.of("cost", 3, "region", 3, "area", 3, "infra", 3, "type", 3);
    }
}
