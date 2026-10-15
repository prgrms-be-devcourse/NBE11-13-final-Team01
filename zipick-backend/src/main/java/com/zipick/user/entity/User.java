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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 계정 정보만 관리
// 추천에 사용하는 자격정보는 USER_PROFILE, 희망 조건은 USER_PREFERENCE에서 관리
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Role role;

    public static User of(Long id, String email, String password, Role role) {
        User user = new User();
        user.id = id;
        user.email = email;
        user.password = password;
        user.role = role;
        return user;
    }

    // 회원가입용
    // 가입 시점에는 일반 사용자 권한만 부여
    public static User of(String email, String encodedPassword) {
        return User.of(null, email, encodedPassword, Role.ROLE_USER);
    }
}
