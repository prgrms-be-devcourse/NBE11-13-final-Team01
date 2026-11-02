package com.zipick.recommendation.repository;

import com.zipick.recommendation.domain.PairwiseChoice;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PairwiseChoiceRepository extends JpaRepository<PairwiseChoice, Long> {
    List<PairwiseChoice> findAllByUserIdOrderByIdAsc(Long userId);
    long countByUserId(Long userId);
    void deleteAllByUserId(Long userId);
}
