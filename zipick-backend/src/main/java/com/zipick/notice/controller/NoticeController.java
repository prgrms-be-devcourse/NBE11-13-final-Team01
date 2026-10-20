package com.zipick.notice.controller;

import com.zipick.notice.dto.NoticePageResponse;
import com.zipick.notice.service.NoticeQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
// SecurityConfig: GET /api/notices/** 는 permitAll
@SecurityRequirements
@RestController
@RequestMapping("/api/notices")
@Tag(name = "공고 조회", description = "수집되어 DB에 저장된 공고와 공급주택을 조건별로 조회합니다.")
public class NoticeController {

    private final NoticeQueryService noticeQueryService;

    public NoticeController(NoticeQueryService noticeQueryService) {
        this.noticeQueryService = noticeQueryService;
    }

    @GetMapping
    @Operation(
            summary = "공고 목록 조회",
            description = "지역·공급유형·주택유형·모집특성·임대료·공고기간 조건으로 공고를 조회합니다. "
                    + "응답에는 공고별 주택, 공급조건, 첨부파일과 미디어 정보가 포함됩니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "공고 목록을 정상적으로 반환합니다."),
            @ApiResponse(responseCode = "400", description = "페이지 범위 또는 조회조건이 올바르지 않은 경우입니다.")
    })
    public NoticePageResponse getNotices(
            @Parameter(description = "조회할 시도 코드입니다.", example = "11")
            @RequestParam(required = false) String brtcCode,
            @Parameter(description = "조회할 시군구 코드입니다.", example = "11680")
            @RequestParam(required = false) String signguCode,
            @Parameter(description = "페이지 번호이며 1부터 시작합니다.", example = "1")
            @RequestParam(defaultValue = "1") @Min(1) int pageNo,
            @Parameter(description = "페이지당 공고 수이며 1~100을 입력합니다.", example = "10")
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int numOfRows,
            @Parameter(description = "공급유형 코드 또는 명칭을 입력합니다.", example = "국민임대")
            @RequestParam(required = false) String suplyTy,
            @Parameter(description = "주택유형 코드 또는 명칭을 입력합니다.", example = "아파트")
            @RequestParam(required = false) String houseTy,
            @Parameter(description = "모집특성 여부로 필터링합니다.")
            @RequestParam(required = false) String lfstsTyAt,
            @Parameter(description = "기본 월임대료 구분으로 필터링합니다.")
            @RequestParam(required = false) String bassMtRntchrgSe,
            @Parameter(description = "공고년월 조회 시작값입니다.", example = "202601")
            @RequestParam(required = false) String yearMtBegin,
            @Parameter(description = "공고년월 조회 종료값입니다.", example = "202612")
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
