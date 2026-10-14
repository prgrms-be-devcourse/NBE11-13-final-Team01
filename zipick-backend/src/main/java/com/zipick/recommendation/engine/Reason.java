package com.zipick.recommendation.engine;

/** 추천 근거 한 줄. item 은 ELIGIBILITY 또는 점수 항목 키(cost/region/area/infra/type). */
public record Reason(Mark mark, String item, String text) {

    public static Reason pass(String item, String text) { return new Reason(Mark.PASS, item, text); }
    public static Reason partial(String item, String text) { return new Reason(Mark.PARTIAL, item, text); }
    public static Reason check(String item, String text) { return new Reason(Mark.CHECK, item, text); }
    public static Reason fail(String item, String text) { return new Reason(Mark.FAIL, item, text); }
}
