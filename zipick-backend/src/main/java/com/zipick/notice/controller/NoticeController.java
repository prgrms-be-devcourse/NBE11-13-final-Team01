package com.zipick.notice.controller;

import com.zipick.notice.dto.NoticePageResponse;
import com.zipick.notice.service.NoticeQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/notices")
public class NoticeController {

    private final NoticeQueryService noticeQueryService;

    public NoticeController(NoticeQueryService noticeQueryService) {
        this.noticeQueryService = noticeQueryService;
    }

    @GetMapping
    public NoticePageResponse getNotices(
            @RequestParam(required = false) String brtcCode,
            @RequestParam(required = false) String signguCode,
            @RequestParam(defaultValue = "1") @Min(1) int pageNo,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int numOfRows,
            @RequestParam(required = false) String suplyTy,
            @RequestParam(required = false) String houseTy,
            @RequestParam(required = false) String lfstsTyAt,
            @RequestParam(required = false) String bassMtRntchrgSe,
            @RequestParam(required = false) String yearMtBegin,
            @RequestParam(required = false) String yearMtEnd
    ) {
        return noticeQueryService.getNotices(
                brtcCode,
                signguCode,
                pageNo,
                numOfRows,
                suplyTy,
                houseTy,
                lfstsTyAt,
                bassMtRntchrgSe,
                yearMtBegin,
                yearMtEnd
        );
    }
}
