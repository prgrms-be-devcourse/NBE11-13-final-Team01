package com.zipick.admin.collection;

import com.zipick.admin.dto.PageResponse;
import com.zipick.external.ai.AiNormalizationStatus;
import com.zipick.global.auth.CurrentUser;
import com.zipick.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 운영자(ROLE_ADMIN)만. 수집기 실행 기록·수동 실행 요청·AI 미완료 문서 */
@Tag(name = "운영: 수집 현황", description = "수집기(기본·AI·인프라) 실행 기록과 수동 실행 요청, AI 미완료 문서 검토")
@RestController
@RequestMapping("/api/v1/admin/collections")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Validated
public class AdminCollectionController {

    private final AdminCollectionService service;

    @Operation(summary = "현황: 종류별 마지막 실행, 실행 중, 대기 요청, 데이터 집계")
    @GetMapping("/overview")
    public ApiResponse<AdminCollectionService.Overview> overview() { return ApiResponse.ok(service.overview()); }

    @Operation(summary = "실행 기록 (최근순)")
    @GetMapping("/runs")
    public ApiResponse<PageResponse<AdminCollectionService.RunResponse>> runs(
            @RequestParam(required = false) CollectionJobType jobType,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.ok(service.runs(jobType, page, size));
    }

    @Operation(summary = "죽은 프로세스의 RUNNING 기록을 실패로 닫기")
    @PostMapping("/runs/{id}/abandon")
    public ApiResponse<AdminCollectionService.RunResponse> abandon(@PathVariable Long id) { return ApiResponse.ok(service.abandon(id)); }

    public record RequestBodyDto(@NotNull CollectionJobType jobType, @Size(max = 200) String note) {}

    @Operation(summary = "수동 실행 요청. 앱 서버 cron(1분)이 집어 collector 컨테이너로 실행한다")
    @PostMapping("/requests")
    public ApiResponse<AdminCollectionService.RequestResponse> request(@CurrentUser Long adminId, @Valid @RequestBody RequestBodyDto body) {
        return ApiResponse.ok(service.request(adminId, body.jobType(), body.note()));
    }

    @Operation(summary = "수동 실행 요청 목록")
    @GetMapping("/requests")
    public ApiResponse<PageResponse<AdminCollectionService.RequestResponse>> requests(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.ok(service.requests(page, size));
    }

    @Operation(summary = "대기 중인 요청 취소")
    @DeleteMapping("/requests/{id}")
    public ApiResponse<AdminCollectionService.RequestResponse> cancel(@PathVariable Long id) { return ApiResponse.ok(service.cancel(id)); }

    @Operation(summary = "AI 미완료 문서 (incomplete/empty/exception)")
    @GetMapping("/ai-documents")
    public ApiResponse<PageResponse<AdminCollectionService.IssueResponse>> issues(
            @RequestParam(defaultValue = "false") boolean acknowledged,
            @RequestParam(required = false) String reason,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size
    ) {
        return ApiResponse.ok(service.issues(acknowledged, reason, page, size));
    }

    public record AcknowledgeBody(@Size(max = 500) String note) {}

    @Operation(summary = "문서를 확인함으로 표시 (목록에서 접힘)")
    @PostMapping("/ai-documents/{id}/acknowledge")
    public ApiResponse<AdminCollectionService.IssueResponse> acknowledge(@CurrentUser Long adminId, @PathVariable Long id,
                                                                         @RequestBody(required = false) AcknowledgeBody body) {
        return ApiResponse.ok(service.acknowledge(adminId, id, body == null ? null : body.note()));
    }

    @Operation(summary = "확인 표시 해제")
    @DeleteMapping("/ai-documents/{id}/acknowledge")
    public ApiResponse<AdminCollectionService.IssueResponse> reopen(@PathVariable Long id) { return ApiResponse.ok(service.reopen(id)); }

    @Operation(summary = "AI 정규화 작업 목록")
    @GetMapping("/normalization-jobs")
    public ApiResponse<PageResponse<AdminCollectionService.NormalizationJobResponse>> normalizationJobs(
            @RequestParam(required = false) AiNormalizationStatus status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size
    ) {
        return ApiResponse.ok(service.normalizationJobs(status, page, size));
    }

    @Operation(summary = "정규화 작업을 다음 AI 실행에서 다시 시도하도록 되돌리기")
    @PostMapping("/normalization-jobs/{id}/requeue")
    public ApiResponse<AdminCollectionService.NormalizationJobResponse> requeue(@PathVariable Long id) { return ApiResponse.ok(service.requeue(id)); }
}
