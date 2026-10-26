package com.zipick.applicationguide.service;

import com.zipick.applicationguide.config.ApplicationGuideProperties;
import com.zipick.applicationguide.config.GuideVersions;
import com.zipick.applicationguide.domain.ApplicationGuide;
import com.zipick.applicationguide.dto.ApplicationGuideResponse;
import com.zipick.applicationguide.dto.GuideAttachment;
import com.zipick.applicationguide.dto.GuideRequestContext;
import com.zipick.applicationguide.dto.GuideResult;
import com.zipick.applicationguide.dto.GuideSourceRef;
import com.zipick.applicationguide.dto.GuideUserContext;
import com.zipick.applicationguide.repository.ApplicationGuideRepository;
import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.global.util.JsonUtil;
import com.zipick.housing.domain.RentalType;
import com.zipick.interest.repository.InterestNoticeRepository;
import com.zipick.notice.domain.Notice;
import com.zipick.notice.repository.NoticeAttachmentRepository;
import com.zipick.notice.repository.NoticeRepository;
import com.zipick.user.domain.UserProfile;
import com.zipick.user.repository.UserProfileRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

/**
 * 가이드 기능의 DB 접근 담당.
 *
 * 문서 다운로드·Gemini 호출은 수십 초가 걸릴 수 있어 트랜잭션 밖에서 돌려야 한다.
 * 그래서 "읽을 것을 한 번에 다 읽는" load 와 "결과만 쓰는" save 로 나눠 두고,
 * 그 사이의 오래 걸리는 일은 ApplicationGuideService 가 트랜잭션 없이 처리한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ApplicationGuidePersistenceService {

    private static final TypeReference<Map<String, GuideSourceRef>> SOURCES_TYPE = new TypeReference<>() {};

    private final InterestNoticeRepository interestRepository;
    private final NoticeRepository noticeRepository;
    private final NoticeAttachmentRepository attachmentRepository;
    private final UserProfileRepository profileRepository;
    private final ApplicationGuideRepository guideRepository;
    private final ApplicationGuideProperties properties;

    /**
     * 가이드 생성·조회에 필요한 것을 한 트랜잭션 안에서 모두 읽는다.
     *
     * 관심 공고로 저장하지 않은 공고는 여기서 막는다. 화면에서만 막으면 API 를 직접 부르면 뚫리고,
     * 무료 AI 사용량이 아무 공고에나 소모될 수 있다.
     */
    public GuideRequestContext load(Long userId, Long noticeId) {
        if (!interestRepository.existsByUserIdAndNoticeId(userId, noticeId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "관심 공고로 저장한 공고에서만 사용할 수 있습니다.");
        }
        Notice notice = noticeRepository.findById(noticeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANNOUNCEMENT_NOT_FOUND));
        UserProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROFILE_NOT_FOUND));

        var header = new ApplicationGuideResponse.NoticeHeader(
                notice.getId(),
                notice.getTitle(),
                notice.getAgency() == null ? null : notice.getAgency().getName(),
                notice.getDetailUrl()
        );
        var attachments = attachmentRepository.findAllByNoticeIdOrderByIdAsc(noticeId).stream()
                .map(a -> new GuideAttachment(a.getId(), a.getAttachmentType(), a.getFileName(), a.getSourceUrl()))
                .toList();

        return new GuideRequestContext(
                header,
                notice.getSource(),
                notice.getNoticeType(),
                RentalType.classify(notice.getNoticeType(), notice.getTitle()),
                attachments,
                GuideUserContext.from(profile, LocalDate.now()),
                profile.getUpdatedAt(),
                toSavedGuide(guideRepository.findByUserIdAndNoticeId(userId, noticeId), profile.getUpdatedAt())
        );
    }

    /** 결과 저장 (user + notice 당 1행, 있으면 덮어쓴다) */
    @Transactional
    public LocalDateTime save(Long userId, Long noticeId, GuideResult result,
                              Map<String, GuideSourceRef> sources, LocalDateTime profileUpdatedAt) {
        LocalDateTime generatedAt = LocalDateTime.now();
        String resultJson = JsonUtil.write(result);
        String sourcesJson = JsonUtil.write(sources);

        guideRepository.findByUserIdAndNoticeId(userId, noticeId)
                .ifPresentOrElse(
                        existing -> existing.refresh(resultJson, sourcesJson, GuideVersions.PROMPT,
                                GuideVersions.REDUCER, properties.model(), profileUpdatedAt, generatedAt),
                        () -> guideRepository.save(new ApplicationGuide(userId, noticeId, resultJson, sourcesJson,
                                GuideVersions.PROMPT, GuideVersions.REDUCER, properties.model(),
                                profileUpdatedAt, generatedAt))
                );
        return generatedAt;
    }

    /** 오늘 만든 가이드 수. 무료 사용량 상한 확인용 */
    public long countGeneratedToday() {
        return guideRepository.countByGeneratedAtAfter(LocalDate.now().atStartOfDay());
    }

    private GuideRequestContext.SavedGuide toSavedGuide(Optional<ApplicationGuide> saved,
                                                        LocalDateTime profileUpdatedAt) {
        return saved.map(guide -> new GuideRequestContext.SavedGuide(
                JsonUtil.read(guide.getResultJson(), new TypeReference<GuideResult>() {}, null),
                JsonUtil.read(guide.getSourcesJson(), SOURCES_TYPE, Map.of()),
                guide.getModel(),
                guide.getGeneratedAt(),
                guide.isReusable(GuideVersions.PROMPT, GuideVersions.REDUCER,
                        properties.model(), profileUpdatedAt)
        )).orElse(null);
    }
}
