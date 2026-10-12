package com.zipick.recommendation.service;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.housing.repository.SupplyHouseRepository;
import com.zipick.recommendation.domain.RecommendationFeedback;
import com.zipick.recommendation.domain.RecommendationFeedback.Reason;
import com.zipick.recommendation.dto.FeedbackRequest;
import com.zipick.recommendation.dto.FeedbackResponse;
import com.zipick.recommendation.repository.RecommendationFeedbackRepository;
import com.zipick.user.domain.UserPreference;
import com.zipick.user.repository.UserPreferenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationFeedbackService {

    private static final int SUGGEST_AFTER = 2;

    private final RecommendationFeedbackRepository repository;
    private final SupplyHouseRepository houseRepository;
    private final UserPreferenceRepository preferenceRepository;

    @Transactional
    public FeedbackResponse submit(Long userId, FeedbackRequest r) {
        if (!houseRepository.existsById(r.houseId())) {
            throw new BusinessException(ErrorCode.HOUSE_NOT_FOUND);
        }
        if (r.type() == RecommendationFeedback.Type.DISLIKE && r.reason() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "'별로예요'에는 이유가 필요합니다.");
        }
        // 같은 집에 다시 남기면 덮어쓴다
        repository.findByUserIdAndSupplyHouseId(userId, r.houseId()).ifPresent(repository::delete);
        RecommendationFeedback saved = repository.save(RecommendationFeedback.builder()
                .userId(userId).supplyHouseId(r.houseId()).type(r.type()).reason(r.reason()).build());

        FeedbackResponse.Suggestion suggestion = null;
        if (r.reason() != null && repository.countByUserIdAndReason(userId, r.reason()) >= SUGGEST_AFTER) {
            suggestion = suggest(userId, r.reason());
        }
        return new FeedbackResponse(saved.getId(), saved.getSupplyHouseId(), saved.getType(), saved.getReason(),
                true, suggestion);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        RecommendationFeedback f = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FEEDBACK_NOT_FOUND));
        repository.delete(f);
    }

    /** 같은 이유가 반복될 때 희망 조건 변경을 제안한다. 반영은 사용자가 수락할 때만. */
    private FeedbackResponse.Suggestion suggest(Long userId, Reason reason) {
        UserPreference p = preferenceRepository.findByUserId(userId).orElse(null);
        if (p == null) return null;
        return switch (reason) {
            case EXPENSIVE -> {
                if (p.getRentMax() == null) yield null;
                int to = Math.max(0, p.getRentMax() - 5);
                yield new FeedbackResponse.Suggestion("rentMax", p.getRentMax(), to,
                        "비싸다는 의견이 " + SUGGEST_AFTER + "번 있었어요. 월 임대료 상한을 " + to + "만 원으로 바꿀까요?");
            }
            case SMALL -> {
                if (p.getAreaMin() == null) yield null;
                var to = p.getAreaMin().add(java.math.BigDecimal.valueOf(5));
                yield new FeedbackResponse.Suggestion("areaMin", p.getAreaMin(), to,
                        "좁다는 의견이 " + SUGGEST_AFTER + "번 있었어요. 희망 최소 면적을 " + to.stripTrailingZeros().toPlainString()
                                + "㎡로 올릴까요?");
            }
            case REGION -> new FeedbackResponse.Suggestion("regionRule", "PREFER", "MUST",
                    "지역이 맞지 않는다는 의견이 " + SUGGEST_AFTER + "번 있었어요. 관심 지역을 '꼭' 조건으로 바꿀까요?");
            case INFRA -> new FeedbackResponse.Suggestion("weights.infra", null, 5,
                    "주변이 불편하다는 의견이 " + SUGGEST_AFTER + "번 있었어요. 인프라 우선순위를 5로 올릴까요?");
            case OLD, ETC -> null;
        };
    }
}
