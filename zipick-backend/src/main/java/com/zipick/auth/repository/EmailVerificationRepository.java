package com.zipick.auth.repository;

import com.zipick.auth.entity.EmailVerification;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {
    Optional<EmailVerification> findByEmail(String email);
    void deleteByEmail(String email);
    /** 만료된 지 오래된 행 정리 (스케줄러) */
    @Modifying
    long deleteByExpiresAtBefore(LocalDateTime before);
}
