package com.zipick.user.dto.response;

import com.zipick.user.entity.AuthProvider;
import com.zipick.user.entity.User;

/**
 * 내 계정 정보.
 *
 * email 은 null 일 수 있다 (카카오가 검증된 이메일을 주지 않은 경우).
 * 프론트는 provider 와 email 을 함께 보고 계정 메뉴 표시를 정한다.
 */
public record UserInfoResponse(
        Long userId,
        String email,
        AuthProvider provider
) {
    public static UserInfoResponse from(User user) {
        return new UserInfoResponse(user.getId(), user.getEmail(), user.getProvider());
    }
}
