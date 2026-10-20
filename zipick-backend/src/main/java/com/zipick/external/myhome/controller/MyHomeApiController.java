package com.zipick.external.myhome.controller;

import com.zipick.external.myhome.client.MyHomeApiClient;
import com.zipick.external.myhome.dto.MyHomeApiResponse;
import com.zipick.external.myhome.dto.MyHomeComplex;
import com.zipick.external.myhome.dto.MyHomeComplexQuery;
import com.zipick.external.myhome.dto.MyHomeNotice;
import com.zipick.external.myhome.dto.MyHomeNoticeQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
// SecurityConfig: /api/external/** 는 permitAll
@SecurityRequirements
@RestController
@RequestMapping("/api/external/myhome")
@Tag(name = "마이홈 원본 API", description = "공공데이터포털 마이홈 API의 공고·임대주택 원본 응답을 조회합니다.")
public class MyHomeApiController {

    private final MyHomeApiClient myHomeApiClient;

    public MyHomeApiController(MyHomeApiClient myHomeApiClient) {
        this.myHomeApiClient = myHomeApiClient;
    }

    @GetMapping("/notices")
    @Operation(
            summary = "마이홈 공공임대 공고 원본 조회",
            description = "마이홈 공공임대 공고 API를 호출해 원본 형식으로 반환합니다. "
                    + "수집 결과가 저장되는 DB는 /api/notices에서 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "마이홈 공고를 정상적으로 반환합니다."),
            @ApiResponse(responseCode = "400", description = "페이지 또는 조회조건이 올바르지 않은 경우입니다."),
            @ApiResponse(responseCode = "500", description = "API 키가 없거나 마이홈 API 호출에 실패한 경우입니다.")
    })
    public MyHomeApiResponse<MyHomeNotice> getNotices(
            @Parameter(description = "조회할 시도 코드입니다.", example = "11")
            @RequestParam(required = false) String brtcCode,
            @Parameter(description = "조회할 시군구 코드입니다.", example = "11680")
            @RequestParam(required = false) String signguCode,
            @Parameter(description = "페이지 번호이며 1부터 시작합니다.", example = "1")
            @RequestParam(defaultValue = "1") @Min(1) int pageNo,
            @Parameter(description = "페이지당 결과 수이며 1~100을 입력합니다.", example = "10")
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int numOfRows,
            @Parameter(description = "마이홈 공급유형 코드를 입력합니다.")
            @RequestParam(required = false) String suplyTy,
            @Parameter(description = "마이홈 주택유형 코드를 입력합니다.")
            @RequestParam(required = false) String houseTy,
            @Parameter(description = "마이홈 모집특성 여부를 입력합니다.")
            @RequestParam(required = false) String lfstsTyAt,
            @Parameter(description = "마이홈 기본 월임대료 구분을 입력합니다.")
            @RequestParam(required = false) String bassMtRntchrgSe,
            @Parameter(description = "공고년월 조회 시작값입니다.", example = "202601")
            @RequestParam(required = false) String yearMtBegin,
            @Parameter(description = "공고년월 조회 종료값입니다.", example = "202612")
            @RequestParam(required = false) String yearMtEnd
    ) {
        MyHomeNoticeQuery query = new MyHomeNoticeQuery(
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
        return myHomeApiClient.getNotices(query);
    }

    @GetMapping("/complexes")
    @Operation(
            summary = "마이홈 임대주택 단지 원본 조회",
            description = "시도·시군구에 속한 임대주택 단지와 기본 임대조건을 마이홈 원본 형식으로 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "마이홈 임대주택 단지를 정상적으로 반환합니다."),
            @ApiResponse(responseCode = "400", description = "필수 지역 코드가 없거나 페이지 범위가 잘못된 경우입니다."),
            @ApiResponse(responseCode = "500", description = "API 키가 없거나 마이홈 API 호출에 실패한 경우입니다.")
    })
    public MyHomeApiResponse<MyHomeComplex> getComplexes(
            @Parameter(description = "필수 시도 코드입니다.", example = "11", required = true)
            @RequestParam @NotBlank String brtcCode,
            @Parameter(description = "필수 시군구 코드입니다.", example = "11680", required = true)
            @RequestParam @NotBlank String signguCode,
            @Parameter(description = "페이지 번호이며 1부터 시작합니다.", example = "1")
            @RequestParam(defaultValue = "1") @Min(1) int pageNo,
            @Parameter(description = "페이지당 결과 수이며 1~100을 입력합니다.", example = "10")
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int numOfRows
    ) {
        return myHomeApiClient.getComplexes(
                new MyHomeComplexQuery(brtcCode, signguCode, pageNo, numOfRows)
        );
    }
}
