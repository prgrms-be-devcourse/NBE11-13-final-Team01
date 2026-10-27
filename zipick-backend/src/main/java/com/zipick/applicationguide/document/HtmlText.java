package com.zipick.applicationguide.document;

/** 상세 페이지에서 긁어낸 문자열에 남는 HTML 엔티티를 되돌린다 */
final class HtmlText {

    private HtmlText() {
    }

    static String decode(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .trim();
    }
}
