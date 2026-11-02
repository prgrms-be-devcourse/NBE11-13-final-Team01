package com.zipick.user.repository;

import com.zipick.user.entity.AuthProvider;
import com.zipick.user.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    /** 알림 채널 정책: 슬랙은 운영자만 */
    boolean existsByIdAndRole(Long id, com.zipick.user.entity.Role role);

    // 소셜 재로그인은 이메일이 아니라 제공자 회원번호로 찾는다.
    Optional<User> findByProviderAndProviderId(AuthProvider provider, String providerId);
}
