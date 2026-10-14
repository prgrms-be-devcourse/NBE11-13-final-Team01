package com.zipick.housing.controller;

import com.zipick.global.response.ApiResponse;
import com.zipick.housing.dto.FilterOptionsResponse;
import com.zipick.housing.dto.HouseFilterParams;
import com.zipick.housing.dto.HouseListResponse;
import com.zipick.housing.service.HouseFilterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 기본 필터링. 공급주택 상세(/{id})는 공급주택 담당 컨트롤러에서 제공한다. */
@Tag(name = "공급주택 필터", description = "전체 공급주택을 조건으로 거른다. 로그인 없이 쓸 수 있다")
@RestController
@RequestMapping("/api/v1/houses")
@RequiredArgsConstructor
public class HouseFilterController {

    private final HouseFilterService service;

    @Operation(summary = "전체 공급주택 (필터)",
            description = "추천 목록과 같은 필터 파라미터를 쓴다. 점수 없이 마감 임박순으로 돌려준다. limit 기본 500, 최대 2000.")
    @GetMapping
    public ApiResponse<HouseListResponse> search(@ModelAttribute HouseFilterParams params,
                                                 @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(service.search(params.toFilter(), limit));
    }

    @Operation(summary = "필터 옵션별 공급주택 수", description = "데이터가 있는 기관·유형만 필터 칩을 켜는 데 쓴다.")
    @GetMapping("/filter-options")
    public ApiResponse<FilterOptionsResponse> options() {
        return ApiResponse.ok(service.options());
    }
}
