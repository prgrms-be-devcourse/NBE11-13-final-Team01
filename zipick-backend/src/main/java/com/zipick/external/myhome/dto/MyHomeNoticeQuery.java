package com.zipick.external.myhome.dto;

public record MyHomeNoticeQuery(
        String brtcCode,
        String signguCode,
        int pageNo,
        int numOfRows,
        String suplyTy,
        String houseTy,
        String lfstsTyAt,
        String bassMtRntchrgSe,
        String yearMtBegin,
        String yearMtEnd
) {
}
