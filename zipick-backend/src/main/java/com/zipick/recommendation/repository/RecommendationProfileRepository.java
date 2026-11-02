package com.zipick.recommendation.repository;

import com.zipick.recommendation.domain.RecommendationProfile;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationProfileRepository extends JpaRepository<RecommendationProfile, Long> {
    List<RecommendationProfile> findAllByUserIdOrderByIdAsc(Long userId);
    Optional<RecommendationProfile> findByUserIdAndActiveTrue(Long userId);
    Optional<RecommendationProfile> findByIdAndUserId(Long id, Long userId);
    long countByUserId(Long userId);
}
