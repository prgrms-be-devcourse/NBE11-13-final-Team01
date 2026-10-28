package com.zipick.notice.controller;

import com.zipick.notice.dto.NoticeSyncResponse;
import com.zipick.notice.service.MyHomeSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// SecurityConfig: /api/internal/** 는 permitAll 이라 누구나 부를 수 있다.
// 운영 웹 서버는 COLLECTOR_API_ENABLED=false 로 이 엔드포인트를 아예 등록하지 않는다. 수집은 collector 프로세스가 맡는다.
@ConditionalOnProperty(prefix = "zipick.collector", name = "api-enabled", havingValue = "true", matchIfMissing = true)
@SecurityRequirements
@RestController
@RequestMapping("/api/internal/notices")
@Tag(name = "공고 수집", description = "마이홈·LH·SH·GH·HUG 공고를 수집하여 DB와 동기화합니다.")
public class NoticeSyncController {

    private final MyHomeSyncService syncService;

    public NoticeSyncController(MyHomeSyncService syncService) {
        this.syncService = syncService;
    }

    @PostMapping("/sync")
    @Operation(
            summary = "공고 수집 및 동기화 실행",
            description = "기관별 모집 중 공고와 공급주택을 수집·갱신하고, 주소 좌표가 없는 주택을 보완합니다. "
                    + "이미 수집 작업이 실행 중이면 중복 실행하지 않습니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수집 및 동기화 결과를 반환합니다."),
            @ApiResponse(responseCode = "409", description = "공고 수집 작업이 이미 실행 중인 경우입니다."),
            @ApiResponse(responseCode = "500", description = "수집 또는 저장 중 서버 오류가 발생한 경우입니다.")
    })
    public NoticeSyncResponse synchronize() {
        return syncService.synchronize();
    }
}
