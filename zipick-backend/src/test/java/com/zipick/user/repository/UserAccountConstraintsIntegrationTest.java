package com.zipick.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zipick.support.PostgresRepositoryTestBase;
import com.zipick.user.entity.AuthProvider;
import com.zipick.user.entity.Role;
import com.zipick.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * LOCAL·KAKAO 계정이 섞인 users 테이블의 유일 제약이 <b>실제 PostgreSQL</b> 에서 어떻게 동작하는지 고정한다.
 *
 * <p>"NULL 은 서로 다른 값으로 본다"는 PostgreSQL 의 규칙에 계정 정책이 통째로 기대고 있다.
 * 이메일 없는 카카오 계정이 여럿 생길 수 있는 것도, LOCAL 계정들이 provider_id 가 모두 NULL 인데도
 * uk_users_provider 에 걸리지 않는 것도 전부 이 규칙 덕분이다. Mock 으로는 확인할 수 없다.
 *
 * <p>제약 위반은 세션을 깨뜨리므로 검증마다 테스트를 나눠 각자 트랜잭션에서 돌린다.
 * Docker 가 없으면 전체가 건너뛴다.
 */
class UserAccountConstraintsIntegrationTest extends PostgresRepositoryTestBase {

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("이메일이 없는 카카오 계정은 providerId 가 다르면 여러 개 저장된다")
    void multipleKakaoAccountsWithoutEmail() {
        // 이메일 동의를 거부한 사용자가 여러 명이어도 uk_users_email 에 걸리면 안 된다.
        userRepository.saveAndFlush(User.ofKakao("1001", null));
        userRepository.saveAndFlush(User.ofKakao("1002", null));

        assertThatCode(() -> userRepository.saveAndFlush(User.ofKakao("1003", null)))
                .doesNotThrowAnyException();

        assertThat(userRepository.findByProviderAndProviderId(AuthProvider.KAKAO, "1002")).isPresent();
    }

    @Test
    @DisplayName("같은 카카오 회원번호는 두 번 저장되지 않는다")
    void duplicateProviderIdIsRejected() {
        userRepository.saveAndFlush(User.ofKakao("2001", null));

        assertThatThrownBy(() -> userRepository.saveAndFlush(User.ofKakao("2001", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("LOCAL 계정은 provider_id 가 모두 NULL 이어도 uk_users_provider 에 걸리지 않는다")
    void localAccountsShareNullProviderId() {
        userRepository.saveAndFlush(User.of("a@example.com", "encoded"));
        userRepository.saveAndFlush(User.of("b@example.com", "encoded"));

        assertThatCode(() -> userRepository.saveAndFlush(User.of("c@example.com", "encoded")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("같은 이메일은 가입 수단이 달라도 두 번 저장되지 않는다")
    void duplicateEmailIsRejectedAcrossProviders() {
        // 카카오가 검증된 이메일을 줬는데 그 이메일로 이미 LOCAL 계정이 있는 상황.
        // 애플리케이션이 existsByEmail 로 먼저 막지만, DB 도 같은 판단을 해야 안전망이 성립한다.
        userRepository.saveAndFlush(User.of("same@example.com", "encoded"));

        assertThatThrownBy(() -> userRepository.saveAndFlush(User.ofKakao("3001", "same@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("이메일이 NULL 인 카카오 계정은 이메일 가입의 중복 검사·조회에 끼어들지 않는다")
    void nullEmailAccountsDoNotDisturbEmailLookup() {
        userRepository.saveAndFlush(User.ofKakao("4001", null));
        userRepository.saveAndFlush(User.ofKakao("4002", null));
        userRepository.saveAndFlush(User.of("local@example.com", "encoded"));

        // 이메일 회원가입의 중복 검사가 NULL 계정 때문에 흔들리지 않는다.
        assertThat(userRepository.existsByEmail("local@example.com")).isTrue();
        assertThat(userRepository.existsByEmail("other@example.com")).isFalse();

        // 비밀번호 로그인 경로(findByEmail)는 이메일 없는 카카오 계정에 절대 닿지 않는다.
        assertThat(userRepository.findByEmail("local@example.com"))
                .get()
                .extracting(User::getProvider)
                .isEqualTo(AuthProvider.LOCAL);
    }

    @Test
    @DisplayName("카카오 계정의 이메일·비밀번호가 NULL 로 저장된다")
    void kakaoAccountStoresNulls() {
        User saved = userRepository.saveAndFlush(User.ofKakao("5001", null));

        assertThat(saved.getEmail()).isNull();
        assertThat(saved.getPassword()).isNull();
        assertThat(saved.getProvider()).isEqualTo(AuthProvider.KAKAO);
        assertThat(saved.getRole()).isEqualTo(Role.ROLE_USER);
    }
}
