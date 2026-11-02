package com.zipick.applicationguide.dto;

import com.zipick.housing.domain.RentalType;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 가이드를 만드는 데 필요한 정보를 트랜잭션 안에서 한 번에 떠 온 묶음.
 *
 * 문서 다운로드와 Gemini 호출은 수십 초가 걸릴 수 있어 트랜잭션 밖에서 돈다.
 * 그래서 DB 에서 읽을 것은 전부 여기 담아 미리 꺼내 두고, 그 뒤로는 DB 를 건드리지 않는다.
 * (주변 인프라 수집이 Collector / PersistenceService 를 나눠 둔 것과 같은 이유다.)
 */
public record GuideRequestContext(
        ApplicationGuideResponse.NoticeHeader header,
        /** 공고 출처 기관 코드. LH 는 공고문 첨부가 DB 에 없어 상세 페이지를 다시 읽어야 한다 */
        String source,
        String noticeType,
        RentalType rentalType,
        List<GuideAttachment> attachments,
        GuideUserContext user,
        LocalDateTime profileUpdatedAt,
        /** 저장돼 있던 결과. 없으면 null */
        SavedGuide saved
) {

    public GuideRequestContext {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    /** 저장돼 있던 가이드. reusable 이 false 면 값은 있지만 지금 기준으로는 다시 만들어야 한다 */
    public record SavedGuide(
            GuideResult result,
            java.util.Map<String, GuideSourceRef> sources,
            String model,
            LocalDateTime generatedAt,
            boolean reusable
    ) {}

    public Long noticeId() {
        return header.noticeId();
    }
}
