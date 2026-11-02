package com.zipick.applicationguide.dto;

/** 가이드를 만들지 못한 이유. 프론트가 이 값으로 안내 문구를 고른다. */
public enum GuideUnavailableReason {

    /** 공고에 분석할 만한 모집공고 파일이 없다 (도면·주택목록만 있거나 첨부 자체가 없음) */
    NO_DOCUMENT("이 공고에서 분석할 수 있는 모집공고 파일을 찾지 못했어요."),
    /** 공고문 다운로드 실패 (404, 타임아웃 등) */
    DOWNLOAD_FAILED("공고문 파일을 내려받지 못했어요."),
    /** PDF/HWP 를 열지 못했다 */
    EXTRACT_FAILED("공고문 파일을 읽지 못했어요."),
    /** 글자가 거의 없다 = 스캔 이미지 문서. OCR 은 범위 밖이다 */
    SCANNED_DOCUMENT("공고문이 이미지로 되어 있어 내용을 읽지 못했어요."),
    /**
     * 파일은 찾았지만 읽을 수 있는 형식이 아니다 (현재는 HWPX).
     * SCANNED_DOCUMENT 와 나눠 둔 이유는 원인이 달라서다. 스캔은 "글자가 없다",
     * 이쪽은 "글자는 있는데 우리가 그 형식을 못 연다" 이고 사용자에게 할 안내도 다르다.
     */
    UNSUPPORTED_DOCUMENT_FORMAT("HWPX 형식의 모집공고문은 현재 AI 분석을 지원하지 않아요. 원문 공고문에서 내용을 확인해주세요."),
    /** 신청자격·제출서류 같은 신청 준비 영역을 찾지 못했다. 전체 문서를 통째로 보내지는 않는다 */
    NO_RELEVANT_SECTION("공고문에서 신청 준비 관련 내용을 찾지 못했어요."),
    /** GEMINI_API_KEY 가 설정되지 않았다 */
    AI_NOT_CONFIGURED("AI 기능이 아직 설정되지 않았어요."),
    /** 무료 사용량 초과 (429) */
    QUOTA_EXCEEDED("오늘 사용할 수 있는 AI 분석 횟수를 모두 썼어요."),
    /** 타임아웃·5xx·JSON 파싱 실패 */
    AI_FAILED("AI 분석에 실패했어요. 잠시 뒤 다시 시도해 주세요.");

    private final String message;

    GuideUnavailableReason(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
