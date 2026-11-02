package com.zipick.user.entity;

import com.zipick.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 계정 정보만 관리
// 추천에 사용하는 자격정보는 USER_PROFILE, 희망 조건은 USER_PREFERENCE에서 관리
@Entity
// provider + provider_id 유일 제약은 동시 최초 로그인 때 같은 카카오 계정이 두 행으로 들어가는 것을 막는
// 안전망이다. 엔티티에 선언해 두지 않으면 local-init.sql 의 DDL 을 엔티티에서 다시 뽑을 때 사라진다.
// (ddl-auto: validate 는 유일 제약을 검사하지 않아 사라져도 기동은 성공한다)
@Table(
        name = "users",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_users_provider",
                columnNames = {"provider", "provider_id"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    // 카카오가 이메일을 주지 않거나 미인증 이메일이면 null 이다.
    // 이메일 가입의 필수 정책은 SignupRequest 검증이 담당하므로 여기서 not null 로 묶지 않는다.
    @Column(unique = true)
    private String email;

    // 소셜 계정은 비밀번호가 없다.
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuthProvider provider;

    // 소셜 제공자가 발급한 고유 회원번호. LOCAL 계정은 null.
    // 카카오 재로그인은 이메일이 아니라 이 값으로 식별한다 (사용자가 카카오 이메일을 바꿔도 같은 계정).
    @Column(name = "provider_id", length = 64)
    private String providerId;

    public static User of(Long id, String email, String password, Role role) {
        return of(id, email, password, role, AuthProvider.LOCAL, null);
    }

    public static User of(Long id, String email, String password, Role role,
                          AuthProvider provider, String providerId) {
        User user = new User();
        user.id = id;
        user.email = email;
        user.password = password;
        user.role = role;
        user.provider = provider;
        user.providerId = providerId;
        return user;
    }

    // 회원가입용
    // 가입 시점에는 일반 사용자 권한만 부여
    public static User of(String email, String encodedPassword) {
        return User.of(null, email, encodedPassword, Role.ROLE_USER);
    }

    /** 운영자 화면에서 권한을 바꿀 때 */
    public void changeRole(Role role) {
        this.role = role;
    }

    /**
     * 카카오 최초 로그인 시 자동 가입.
     *
     * email 은 카카오가 검증된 값을 줬을 때만 채우고, 그 외에는 null 로 둔다.
     * 가짜 이메일을 만들어 넣지 않는다.
     */
    public static User ofKakao(String providerId, String verifiedEmail) {
        return User.of(null, verifiedEmail, null, Role.ROLE_USER, AuthProvider.KAKAO, providerId);
    }

    /** 비밀번호 로그인을 쓸 수 있는 계정인지. 소셜 계정은 비밀번호 자체가 없다. */
    public boolean isLocalAccount() {
        return provider == AuthProvider.LOCAL;
    }
}
