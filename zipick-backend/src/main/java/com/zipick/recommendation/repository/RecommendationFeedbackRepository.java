package com.zipick.recommendation.repository;

import com.zipick.recommendation.domain.RecommendationFeedback;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationFeedbackRepository extends JpaRepository<RecommendationFeedback, Long> {
    List<RecommendationFeedback> findAllByUserId(Long userId);
    Optional<RecommendationFeedback> findByIdAndUserId(Long id, Long userId);
    Optional<RecommendationFeedback> findByUserIdAndSupplyHouseId(Long userId, Long supplyHouseId);
    long countByUserIdAndReason(Long userId, RecommendationFeedback.Reason reason);
}
