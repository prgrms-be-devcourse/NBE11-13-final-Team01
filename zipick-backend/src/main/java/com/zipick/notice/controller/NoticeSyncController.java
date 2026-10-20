package com.zipick.notice.controller;

import com.zipick.notice.dto.NoticeSyncResponse;
import com.zipick.notice.service.MyHomeSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/notices")
@Tag(name = "공고 수집", description = "마이홈·LH·SH 공고를 수집하여 DB와 동기화합니다.")
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
