package com.zipick.applicationguide.dto;

import java.util.List;

/**
 * Gemini 가 만들어 내는 신청 준비 가이드 본문. 이 구조가 곧 Gemini 에 보내는 responseJsonSchema 이기도 하다.
 *
 * 설계 원칙
 * - AI 는 "최종 지원 가능/불가" 를 말하지 않는다. 그래서 FAIL 에 해당하는 상태가 없다.
 * - AI 는 페이지 번호를 직접 쓰지 않는다. 우리가 준 SOURCE ID(S1, S2...)만 돌려주고
 *   실제 파일명·페이지 변환은 서버가 한다. 출처를 지어내지 못하게 하는 장치다.
 * - 공고문에 없는 내용은 만들지 않는다. 애매하면 빼지 말고 CHECK_REQUIRED / CONDITIONAL 로 남긴다.
 */
public record GuideResult(
        String summary,
        List<PersonalCheck> personalChecks,
        List<DocumentItem> documents,
        List<Caution> cautions
) {

    public GuideResult {
        personalChecks = personalChecks == null ? List.of() : List.copyOf(personalChecks);
        documents = documents == null ? List.of() : List.copyOf(documents);
        cautions = cautions == null ? List.of() : List.copyOf(cautions);
    }

    /** 내 정보 기준 확인사항 한 줄 */
    public record PersonalCheck(
            String title,
            CheckStatus status,
            String detail,
            List<String> sourceIds
    ) {
        public PersonalCheck {
            sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
        }
    }

    /** 제출서류 한 건 */
    public record DocumentItem(
            String name,
            DocumentCategory category,
            /** 왜 이 사용자에게 필요한지 */
            String reason,
            /** 공고문에 적힌 발급일·원본 여부·제출 시점 등. 공고문에 없으면 비운다 */
            String requirements,
            List<String> sourceIds
    ) {
        public DocumentItem {
            sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
        }
    }

    /** 꼭 확인할 내용 한 줄 */
    public record Caution(
            CautionKind kind,
            String text,
            List<String> sourceIds
    ) {
        public Caution {
            sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
        }
    }

    /**
     * MATCHED        현재 입력정보와 공고 조건이 일치
     * CHECK_REQUIRED 현재 정보만으로 확정할 수 없어 추가 확인 필요
     * NOT_APPLICABLE 현재 정보상 해당하지 않는 것으로 보임 (공고문이 명시적으로 제외한 경우에만)
     */
    public enum CheckStatus { MATCHED, CHECK_REQUIRED, NOT_APPLICABLE }

    /**
     * COMMON       대부분의 신청자에게 공통으로 요구되는 서류
     * PERSONALIZED 현재 사용자 상황에 맞춰 필요할 가능성이 높은 서류
     * CONDITIONAL  신청순위·추가 조건에 따라 필요한 서류 (애매하면 여기에 남긴다)
     */
    public enum DocumentCategory { COMMON, PERSONALIZED, CONDITIONAL }

    /**
     * NOTICE 공고문에서 실제 확인된 유의사항
     * MANUAL 현재 사용자 정보만으로 판단할 수 없어 사용자가 직접 확인해야 하는 내용
     */
    public enum CautionKind { NOTICE, MANUAL }
}
