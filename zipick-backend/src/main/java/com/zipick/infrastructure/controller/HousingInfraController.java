package com.zipick.infrastructure.controller;

import com.zipick.global.response.ApiResponse;
import com.zipick.infrastructure.dto.HousingInfraResponse;
import com.zipick.infrastructure.service.HousingInfraQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
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
@Tag(name = "주변 생활 인프라", description = "공급주택 좌표 기준 주변 생활시설. "
        + "서버 배치가 미리 수집해 둔 값을 읽기만 하며, 이 API가 카카오를 호출하지는 않는다.")
// SecurityConfig: GET /api/v1/map/housings/** 는 permitAll
@SecurityRequirements
@RestController
@RequestMapping("/api/v1/map/housings")
public class HousingInfraController {

    private final HousingInfraQueryService service;

    public HousingInfraController(HousingInfraQueryService service) {
        this.service = service;
    }

    @Operation(summary = "공급주택 주변 생활 인프라 조회", description = """
            로그인 없이 조회할 수 있다. 카테고리 6종(지하철역·대형마트·편의점·병원·약국·학교)에 대해
            가장 가까운 시설 1곳과 반경 내 전체 시설 수를 돌려준다. 검색 반경은 모두 1,500m다.

            - 검색 결과가 0건인 카테고리는 응답에서 빠진다. 순서는 서버 설정 순서를 따른다.
            - totalCount 는 저장된 시설 수가 아니라 카카오 meta.total_count(반경 내 전체 수)다.
            - nearestDistanceMeter 는 직선거리다. 실제 도보 경로나 도보 시간이 아니다.
            - 주택이 없거나 모집 중이 아니면 404 HOUSE_NOT_FOUND.

            status 4종
            - FRESH: 유효한 수집 결과. categories 가 비어 있으면 반경 안에 시설이 없다는 뜻이다
            - STALE: 이전 수집 결과는 있으나 TTL·정책·좌표 변경으로 갱신 대기 중. 이전 값을 그대로 보여 준다
            - PENDING: 좌표는 있으나 아직 수집 전. 다음 배치 주기에 채워진다
            - UNAVAILABLE: 좌표가 없어 제공할 수 없다
            """)
    @GetMapping("/{housingId}/infra")
    public ApiResponse<HousingInfraResponse> infra(@PathVariable Long housingId) {
        return ApiResponse.ok(service.find(housingId));
    }
}
