package com.zipick.recommendation.dto;

import com.zipick.recommendation.engine.Mark;
import com.zipick.recommendation.engine.Reason;

public record ReasonResponse(Mark mark, String item, String text) {
    public static ReasonResponse from(Reason r) {
        return new ReasonResponse(r.mark(), r.item(), r.text());
    }
}
