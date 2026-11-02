package com.zipick.admin.user;

import com.zipick.admin.AdminQueryRepository;
import com.zipick.admin.dto.PageResponse;
import com.zipick.auth.dto.response.EmailVerificationSendResponse;
import com.zipick.auth.entity.EmailVerification;
import com.zipick.auth.repository.EmailVerificationRepository;
import com.zipick.auth.service.EmailVerificationService;
import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.notification.client.NotificationClient;
import com.zipick.notification.domain.NotificationSetting;
import com.zipick.notification.dto.NotificationEventResponse;
import com.zipick.notification.repository.NotificationSettingRepository;
import com.zipick.user.dto.UserPreferenceResponse;
import com.zipick.user.dto.UserProfileResponse;
import com.zipick.user.entity.Role;
import com.zipick.user.entity.User;
import com.zipick.user.repository.UserRepository;
import com.zipick.user.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/** 운영자 화면 "회원": 목록·상세·권한, 가입 이메일 인증 지원 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminUserService {

    private final UserRepository users;
    private final AdminQueryRepository query;
    private final UserProfileService profiles;
    private final NotificationSettingRepository settings;
    private final NotificationClient notifications;
    private final EmailVerificationRepository verifications;
    private final EmailVerificationService verificationService;

    public record UserRow(Long userId, String email, String role, LocalDateTime createdAt,
                          boolean hasProfile, boolean hasPreference, boolean onboarded,
                          boolean notificationEnabled, long interestCount) {
    }

    public record SettingView(boolean enabled, String email, boolean slackConfigured, boolean marketingConsent,
                              LocalDateTime marketingConsentedAt, String deadlineDays, boolean newMatchEnabled) {
    }

    public record UserDetail(UserRow user, UserProfileResponse profile, UserPreferenceResponse preference,
                             SettingView notificationSetting, List<NotificationEventResponse> notifications,
                             String notificationError) {
    }

    public PageResponse<UserRow> users(String q, int page, int size) {
        String keyword = q == null ? "" : q.trim();
        long total = query.countUsersMatching(keyword);
        List<UserRow> rows = query.findUsers(keyword, (page - 1) * size, size).stream().map(AdminUserService::toRow).toList();
        int totalPages = (int) Math.ceil(total / (double) size);
        return new PageResponse<>(page, size, total, totalPages, rows);
    }

    public UserDetail detail(Long userId) {
        User user = users.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        UserProfileResponse profile = quietly(() -> profiles.getProfile(userId));
        UserPreferenceResponse preference = quietly(() -> profiles.getPreference(userId));
        SettingView setting = settings.findById(userId).map(AdminUserService::toView).orElse(null);
        List<NotificationEventResponse> history = List.of();
        String error = null;
        try {
            if (notifications.configured()) history = notifications.history(userId);
        } catch (BusinessException e) {
            error = e.getMessage();
        }
        UserRow row = new UserRow(user.getId(), user.getEmail(), user.getRole() == null ? null : user.getRole().name(),
                user.getCreatedAt(), profile != null, preference != null, profile != null && preference != null,
                setting != null && setting.enabled(), 0);
        return new UserDetail(row, profile, preference, setting, history, error);
    }

    @Transactional
    public UserRow changeRole(Long adminId, Long userId, Role role) {
        if (adminId != null && adminId.equals(userId) && role != Role.ROLE_ADMIN) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "자기 자신의 운영자 권한은 내릴 수 없습니다. 다른 운영자에게 요청해 주세요.");
        }
        User user = users.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        user.changeRole(role);
        log.info("운영자 {} 가 사용자 {} 권한을 {} 로 변경", adminId, userId, role);
        return detail(userId).user();
    }

    // ---- 가입 이메일 인증 지원 ("메일이 안 와요" 문의) ----

    public record VerificationRow(Long id, String email, LocalDateTime sentAt, LocalDateTime expiresAt, int attempts,
                                  int sendCount, LocalDateTime verifiedAt, boolean expired) {
    }

    /** 가입을 끝내지 않은(행이 남아 있는) 인증 시도 목록 */
    public List<VerificationRow> verifications() {
        LocalDateTime now = LocalDateTime.now();
        return verifications.findAll(Sort.by(Sort.Direction.DESC, "sentAt")).stream()
                .map(v -> new VerificationRow(v.getId(), v.getEmail(), v.getSentAt(), v.getExpiresAt(), v.getAttempts(),
                        v.getSendCount(), v.getVerifiedAt(), v.getExpiresAt() != null && v.getExpiresAt().isBefore(now)))
                .toList();
    }

    /** 사용자 대신 코드를 다시 보낸다. 재전송 간격·일일 횟수 규칙은 그대로 적용된다 */
    @Transactional
    public EmailVerificationSendResponse resend(String email) {
        return verificationService.send(email);
    }

    /** 인증 행을 지워 틀린 횟수·일일 전송 횟수를 초기화한다. 사용자가 처음부터 다시 받을 수 있다 */
    @Transactional
    public void reset(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        EmailVerification v = verifications.findByEmail(normalized).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "해당 이메일의 인증 기록이 없습니다."));
        verifications.delete(v);
    }

    private static UserRow toRow(AdminQueryRepository.UserRow r) {
        return new UserRow(r.userId(), r.email(), r.role(), r.createdAt(), r.hasProfile(), r.hasPreference(),
                r.hasProfile() && r.hasPreference(), r.notificationEnabled(), r.interestCount());
    }

    private static SettingView toView(NotificationSetting s) {
        return new SettingView(s.isEnabled(), s.getEmail(), s.getSlackWebhookUrl() != null && !s.getSlackWebhookUrl().isBlank(),
                s.isMarketingConsent(), s.getMarketingConsentedAt(), s.getDeadlineDays(), s.isNewMatchEnabled());
    }

    private static <T> T quietly(java.util.function.Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (BusinessException e) {
            return null;
        }
    }
}
