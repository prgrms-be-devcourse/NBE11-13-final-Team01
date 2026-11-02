package com.zipick.external.myhome.dto;

public record MyHomeComplexQuery(
        String brtcCode,
        String signguCode,
        int pageNo,
        int numOfRows
) {
}
