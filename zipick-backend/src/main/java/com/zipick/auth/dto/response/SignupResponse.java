package com.zipick.auth.dto.response;

import com.zipick.user.entity.User;

public record SignupResponse(
        Long userId,
        String email
) {
    public static SignupResponse from(User user) {
        return new SignupResponse(user.getId(), user.getEmail());
    }
}
