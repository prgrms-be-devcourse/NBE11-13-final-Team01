package com.zipick.recommendation.engine;

import java.util.List;

/** 지원조건 판정 결과. */
public record EligibilityResult(Status status, List<Reason> reasons) {

    public enum Status { PASS, CHECK, FAIL }

    public boolean excluded() {
        return status == Status.FAIL;
    }
}
