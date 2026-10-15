package com.zipick.user.dto.response;

import com.zipick.user.entity.User;

public record UserInfoResponse(
        Long userId,
        String email
) {
    public static UserInfoResponse from(User user) {
        return new UserInfoResponse(user.getId(), user.getEmail());
    }
}
