package com.zipick.applicationguide.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.zipick.applicationguide.config.ApplicationGuideProperties;
import com.zipick.applicationguide.dto.GuideRequestContext;
import com.zipick.applicationguide.repository.ApplicationGuideRepository;
import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.housing.domain.RentalType;
import com.zipick.interest.repository.InterestNoticeRepository;
import com.zipick.notice.domain.Notice;
import com.zipick.notice.domain.NoticeStatus;
import com.zipick.notice.repository.NoticeAttachmentRepository;
import com.zipick.notice.repository.NoticeRepository;
import com.zipick.user.domain.StudentStatus;
import com.zipick.user.domain.UserProfile;
import com.zipick.user.repository.UserProfileRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 가이드를 만들기 전에 막아야 하는 것들.
 *
 * 특히 "관심 공고로 저장하지 않은 공고" 는 화면에서만 막으면 안 된다.
 * API 를 직접 부르면 아무 공고에나 무료 AI 사용량을 쓸 수 있게 된다.
 */
@ExtendWith(MockitoExtension.class)
class ApplicationGuidePersistenceServiceTest {

    @Mock InterestNoticeRepository interestRepository;
    @Mock NoticeRepository noticeRepository;
    @Mock NoticeAttachmentRepository attachmentRepository;
    @Mock UserProfileRepository profileRepository;
    @Mock ApplicationGuideRepository guideRepository;

    private static ApplicationGuideProperties properties(String model) {
        return new ApplicationGuideProperties(model, null, null, 0, 0, 0, 0, null);
    }

    private ApplicationGuidePersistenceService service() {
        return new ApplicationGuidePersistenceService(interestRepository, noticeRepository, attachmentRepository,
                profileRepository, guideRepository, properties("flash-lite"));
    }

    private static Notice notice() {
        return new Notice("LH", "ext-1", "2026 청년매입임대 모집", NoticeStatus.OPEN);
    }

    private static UserProfile profile() {
        return UserProfile.builder()
                .userId(1L).birthDate(LocalDate.of(2002, 3, 1)).married(false).householdSize(1)
                .homeless(true).studentStatus(StudentStatus.UNIVERSITY).build();
    }

    @Test
    void 관심_공고로_저장하지_않았으면_막는다() {
        when(interestRepository.existsByUserIdAndNoticeId(1L, 10L)).thenReturn(false);

        assertThatThrownBy(() -> service().load(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void 공고가_없으면_공고_없음으로_끝난다() {
        when(interestRepository.existsByUserIdAndNoticeId(1L, 10L)).thenReturn(true);
        when(noticeRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().load(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ANNOUNCEMENT_NOT_FOUND);
    }

    @Test
    void 내_정보를_입력하지_않았으면_먼저_입력하라고_안내한다() {
        when(interestRepository.existsByUserIdAndNoticeId(1L, 10L)).thenReturn(true);
        when(noticeRepository.findById(10L)).thenReturn(Optional.of(notice()));
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().load(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PROFILE_NOT_FOUND);
    }

    @Test
    void 공고와_사용자_정보를_한_번에_읽어_온다() {
        when(interestRepository.existsByUserIdAndNoticeId(1L, 10L)).thenReturn(true);
        when(noticeRepository.findById(10L)).thenReturn(Optional.of(notice()));
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.of(profile()));
        when(attachmentRepository.findAllByNoticeIdOrderByIdAsc(10L)).thenReturn(List.of());
        when(guideRepository.findByUserIdAndNoticeId(1L, 10L)).thenReturn(Optional.empty());

        GuideRequestContext context = service().load(1L, 10L);

        assertThat(context.source()).isEqualTo("LH");
        // 공고 제목만으로도 공공임대 유형이 분류된다 (notice_type 이 비어 있는 공고가 많다)
        assertThat(context.rentalType()).isEqualTo(RentalType.YOUTH_PURCHASE);
        assertThat(context.saved()).isNull();
        // Gemini 에 보내는 사용자 정보는 이 5개뿐이다. 소득·자산은 애초에 담기지 않는다
        assertThat(context.user().studentStatus()).isEqualTo(StudentStatus.UNIVERSITY);
        assertThat(context.user().homeless()).isTrue();
        assertThat(context.user().householdSize()).isEqualTo(1);
    }
}
