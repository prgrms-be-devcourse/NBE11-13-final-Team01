package com.zipick.applicationguide.dto;

/**
 * SOURCE ID 가 가리키는 실제 위치. "S1" -> 모집공고.pdf 24쪽.
 *
 * page 는 PDF 일 때만 채운다. HWP 는 Tika 가 페이지 경계를 주지 않으므로
 * page 대신 section(문단 첫 줄에서 뽑은 제목 추정값)으로 대신한다.
 */
public record GuideSourceRef(
        String fileName,
        Integer page,
        String section
) {
    public static GuideSourceRef ofPage(String fileName, int page) {
        return new GuideSourceRef(fileName, page, null);
    }

    public static GuideSourceRef ofSection(String fileName, String section) {
        return new GuideSourceRef(fileName, null, section);
    }

    /** 화면에 그대로 쓸 수 있는 한 줄 표기 */
    public String label() {
        if (page != null) return fileName + " " + page + "쪽";
        if (section != null && !section.isBlank()) return fileName + " · " + section;
        return fileName;
    }
}
