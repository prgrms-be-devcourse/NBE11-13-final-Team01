package com.zipick.user.dto.response;

import com.zipick.user.entity.User;

/** role 은 프론트가 운영자 메뉴를 보일지 정할 때만 쓴다. 권한 검사는 서버(SecurityConfig)가 한다 */
public record UserInfoResponse(
        Long userId,
        String email,
        String role
) {
    public static UserInfoResponse from(User user) {
        return new UserInfoResponse(user.getId(), user.getEmail(), user.getRole() == null ? null : user.getRole().name());
    }
}
