package com.zipick.admin.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zipick.admin.AdminQueryRepository;
import com.zipick.auth.dto.response.EmailVerificationSendResponse;
import com.zipick.auth.entity.EmailVerification;
import com.zipick.auth.repository.EmailVerificationRepository;
import com.zipick.auth.service.EmailVerificationService;
import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.notification.client.NotificationClient;
import com.zipick.notification.domain.NotificationSetting;
import com.zipick.notification.repository.NotificationSettingRepository;
import com.zipick.user.entity.Role;
import com.zipick.user.entity.User;
import com.zipick.user.repository.UserRepository;
import com.zipick.user.service.UserProfileService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

/** 운영자 회원 화면: 목록·상세는 부가 정보가 없어도 뜨고, 자기 권한은 내릴 수 없다 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock UserRepository users;
    @Mock AdminQueryRepository query;
    @Mock UserProfileService profiles;
    @Mock NotificationSettingRepository settings;
    @Mock NotificationClient notifications;
    @Mock EmailVerificationRepository verifications;
    @Mock EmailVerificationService verificationService;
    AdminUserService service;

    @BeforeEach
    void setUp() {
        service = new AdminUserService(users, query, profiles, settings, notifications, verifications, verificationService);
    }

    @Test
    void 목록은_검색어를_다듬어_페이지로_돌려준다() {
        when(query.countUsersMatching("kim")).thenReturn(21L);
        when(query.findUsers("kim", 20, 20)).thenReturn(List.of(
                new AdminQueryRepository.UserRow(1L, "kim@x.com", "ROLE_USER", LocalDateTime.now(), true, false, true, 2)));

        var page = service.users(" kim ", 2, 20);

        assertThat(page.totalElements()).isEqualTo(21);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).onboarded()).isFalse();
        assertThat(page.items().get(0).interestCount()).isEqualTo(2);
    }

    @Test
    void 검색어가_없으면_빈_문자열로_조회한다() {
        when(query.countUsersMatching("")).thenReturn(0L);
        when(query.findUsers(anyString(), anyInt(), anyInt())).thenReturn(List.of());
        assertThat(service.users(null, 1, 10).items()).isEmpty();
    }

    @Test
    void 상세는_프로필_설정_알림_이력이_없어도_뜬다() {
        User user = User.of(5L, "a@x.com", "pw", Role.ROLE_USER);
        when(users.findById(5L)).thenReturn(Optional.of(user));
        when(profiles.getProfile(5L)).thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        when(profiles.getPreference(5L)).thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        when(settings.findById(5L)).thenReturn(Optional.empty());
        when(notifications.configured()).thenReturn(false);

        AdminUserService.UserDetail detail = service.detail(5L);

        assertThat(detail.user().userId()).isEqualTo(5L);
        assertThat(detail.user().role()).isEqualTo("ROLE_USER");
        assertThat(detail.user().hasProfile()).isFalse();
        assertThat(detail.user().onboarded()).isFalse();
        assertThat(detail.user().notificationEnabled()).isFalse();
        assertThat(detail.profile()).isNull();
        assertThat(detail.notificationSetting()).isNull();
        assertThat(detail.notifications()).isEmpty();
        assertThat(detail.notificationError()).isNull();
    }

    @Test
    void 상세는_알림센터_오류를_메시지로_담고_설정은_요약한다() {
        User user = User.of(5L, "a@x.com", "pw", Role.ROLE_ADMIN);
        when(users.findById(5L)).thenReturn(Optional.of(user));
        NotificationSetting setting = NotificationSetting.defaults(5L);
        when(settings.findById(5L)).thenReturn(Optional.of(setting));
        when(notifications.configured()).thenReturn(true);
        when(notifications.history(5L)).thenThrow(new BusinessException(ErrorCode.NOTIFICATION_UNAVAILABLE, "알림센터 연결 실패"));

        AdminUserService.UserDetail detail = service.detail(5L);

        assertThat(detail.notificationError()).isEqualTo("알림센터 연결 실패");
        assertThat(detail.notificationSetting().slackConfigured()).isFalse();
        assertThat(detail.notificationSetting().deadlineDays()).isEqualTo(NotificationSetting.DEFAULT_DEADLINE_DAYS);
    }

    @Test
    void 없는_회원은_USER_NOT_FOUND() {
        when(users.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.detail(99L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    void 자기_자신의_운영자_권한은_내릴_수_없다() {
        assertThatThrownBy(() -> service.changeRole(1L, 1L, Role.ROLE_USER))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DATA_CONFLICT);
        verify(users, never()).findById(any());
    }

    @Test
    void 권한을_바꾸면_바뀐_행을_돌려준다() {
        User user = User.of(7L, "b@x.com", "pw", Role.ROLE_USER);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(settings.findById(7L)).thenReturn(Optional.empty());
        when(notifications.configured()).thenReturn(false);

        AdminUserService.UserRow row = service.changeRole(1L, 7L, Role.ROLE_ADMIN);

        assertThat(user.getRole()).isEqualTo(Role.ROLE_ADMIN);
        assertThat(row.role()).isEqualTo("ROLE_ADMIN");
    }

    @Test
    void 인증_목록은_만료_여부를_같이_준다() {
        LocalDateTime now = LocalDateTime.now();
        EmailVerification expired = EmailVerification.issue("old@x.com", "h", now.minusHours(2), now.minusHours(1));
        EmailVerification live = EmailVerification.issue("new@x.com", "h", now, now.plusMinutes(10));
        when(verifications.findAll(any(Sort.class))).thenReturn(List.of(live, expired));

        List<AdminUserService.VerificationRow> rows = service.verifications();

        assertThat(rows).extracting(AdminUserService.VerificationRow::expired).containsExactly(false, true);
        assertThat(rows.get(1).email()).isEqualTo("old@x.com");
    }

    @Test
    void 재전송은_기존_규칙_그대로_보낸다() {
        when(verificationService.send("a@x.com")).thenReturn(new EmailVerificationSendResponse(true, 10, 60));
        assertThat(service.resend("a@x.com").resendAfterSeconds()).isEqualTo(60);
    }

    @Test
    void 초기화는_이메일을_정규화해_찾고_없으면_RESOURCE_NOT_FOUND() {
        EmailVerification v = EmailVerification.issue("a@x.com", "h", LocalDateTime.now(), LocalDateTime.now().plusMinutes(5));
        when(verifications.findByEmail("a@x.com")).thenReturn(Optional.of(v));
        service.reset("  A@X.com ");
        verify(verifications).delete(v);

        when(verifications.findByEmail("none@x.com")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reset("none@x.com"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }
}
