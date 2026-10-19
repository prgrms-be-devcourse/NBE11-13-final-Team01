package com.zipick.infrastructure.controller;

import com.zipick.global.response.ApiResponse;
import com.zipick.infrastructure.dto.HousingInfraResponse;
import com.zipick.infrastructure.service.HousingInfraQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공급주택 주변 생활 인프라 조회.
 *
 * 지도 상세(MapHousingController)와 같은 경로 아래 두어 기존 permitAll 규칙을 그대로 쓴다.
 * 상세 응답과 합치지 않고 따로 둔 이유는, 프론트가 두 요청을 병렬로 보내 상세 화면이 먼저 뜨게 하기 위해서다.
 */
@RestController
@RequestMapping("/api/v1/map/housings")
public class HousingInfraController {

    private final HousingInfraQueryService service;

    public HousingInfraController(HousingInfraQueryService service) {
        this.service = service;
    }

    @GetMapping("/{housingId}/infra")
    public ApiResponse<HousingInfraResponse> infra(@PathVariable Long housingId) {
        return ApiResponse.ok(service.find(housingId));
    }
}
