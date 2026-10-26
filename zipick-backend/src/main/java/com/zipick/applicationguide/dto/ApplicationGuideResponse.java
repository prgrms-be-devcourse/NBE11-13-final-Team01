package com.zipick.applicationguide.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 가이드 화면에 내려주는 응답.
 *
 * status 가 READY 일 때만 guide·sources 가 차고, UNAVAILABLE 이면 reason 이 찬다.
 * noticeDetailUrl 은 어떤 상태에서도 항상 채운다. AI 가 실패해도 사용자는 원문을 볼 수 있어야 한다.
 */
public record ApplicationGuideResponse(
        Long noticeId,
        String noticeTitle,
        String agencyName,
        String noticeDetailUrl,
        GuideStatus status,
        GuideUnavailableReason reason,
        String reasonMessage,
        GuideResult guide,
        /** "S1" -> 모집공고.pdf 24쪽 */
        Map<String, GuideSourceRef> sources,
        String model,
        LocalDateTime generatedAt,
        /** true 면 저장돼 있던 결과를 그대로 돌려준 것 (Gemini 를 호출하지 않았다) */
        boolean cached
) {

    public static ApplicationGuideResponse notGenerated(NoticeHeader header) {
        return new ApplicationGuideResponse(header.noticeId(), header.title(), header.agencyName(), header.detailUrl(),
                GuideStatus.NOT_GENERATED, null, null, null, Map.of(), null, null, false);
    }

    public static ApplicationGuideResponse unavailable(NoticeHeader header, GuideUnavailableReason reason) {
        return new ApplicationGuideResponse(header.noticeId(), header.title(), header.agencyName(), header.detailUrl(),
                GuideStatus.UNAVAILABLE, reason, reason.getMessage(), null, Map.of(), null, null, false);
    }

    public static ApplicationGuideResponse ready(NoticeHeader header, GuideResult guide,
                                                 Map<String, GuideSourceRef> sources,
                                                 String model, LocalDateTime generatedAt, boolean cached) {
        return new ApplicationGuideResponse(header.noticeId(), header.title(), header.agencyName(), header.detailUrl(),
                GuideStatus.READY, null, null, guide, sources, model, generatedAt, cached);
    }

    /** 어떤 상태에서도 화면 머리말에 필요한 공고 정보 */
    public record NoticeHeader(Long noticeId, String title, String agencyName, String detailUrl) {}
}
