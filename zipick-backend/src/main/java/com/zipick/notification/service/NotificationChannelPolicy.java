package com.zipick.notification.service;

import com.zipick.user.entity.Role;
import com.zipick.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 채널 정책: 슬랙(웹훅)은 운영자(ROLE_ADMIN)만, 일반 사용자는 이메일만.
 * 설정을 저장할 때도, 실제로 보낼 때(스케줄러·캠페인·테스트)도 같은 판단을 쓴다.
 */
@Component
@RequiredArgsConstructor
public class NotificationChannelPolicy {

    private final UserRepository users;

    public boolean slackAllowed(Long userId) {
        return userId != null && users.existsByIdAndRole(userId, Role.ROLE_ADMIN);
    }
}
