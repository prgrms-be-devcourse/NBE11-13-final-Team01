package com.zipick.notice.controller;

import com.zipick.global.response.ApiResponse;
import com.zipick.notice.dto.MapHousingDetailResponse;
import com.zipick.notice.dto.MapHousingListResponse;
import com.zipick.notice.service.MapHousingAssetService;
import com.zipick.notice.service.MapHousingQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// SecurityConfig: GET /api/v1/map/housings/** 는 permitAll
@SecurityRequirements
@RestController
@RequestMapping("/api/v1/map/housings")
@Tag(name = "공급주택 지도", description = "모집 중인 공급주택의 지도 좌표, 상세정보, 첨부파일과 이미지를 조회합니다.")
public class MapHousingController {

    private final MapHousingQueryService service;
    private final MapHousingAssetService assetService;

    public MapHousingController(MapHousingQueryService service, MapHousingAssetService assetService) {
        this.service = service;
        this.assetService = assetService;
    }

    @GetMapping
    @Operation(
            summary = "지도에 표시할 공급주택 목록 조회",
            description = "모집 중이며 좌표가 확인된 공급주택을 반환합니다. 같은 위치의 여러 공급조건은 주택 하나로 묶습니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "지도용 공급주택 목록을 정상적으로 반환합니다.")
    })
    public ApiResponse<MapHousingListResponse> list() {
        return ApiResponse.ok(service.findOpenHousings());
    }

    @GetMapping("/{housingId}")
    @Operation(
            summary = "공급주택 상세정보 조회",
            description = "선택한 지도 핀의 공고, 임대조건, 주택시설, 첨부파일과 이미지 목록을 반환합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "공급주택 상세정보를 정상적으로 반환합니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "공급주택을 찾을 수 없는 경우입니다.")
    })
    public ApiResponse<MapHousingDetailResponse> detail(
            @Parameter(description = "조회할 공급주택 ID입니다.", example = "1") @PathVariable Long housingId
    ) {
        return ApiResponse.ok(service.findDetail(housingId));
    }

    @GetMapping("/{housingId}/attachments/{attachmentId}/content")
    @Operation(
            summary = "공고 첨부파일 내용 조회",
            description = "해당 주택 공고에 속한 PDF·HWP·XLSX 등의 첨부파일을 브라우저에서 볼 수 있도록 반환합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "첨부파일을 정상적으로 반환합니다.",
                    content = @Content(
                            mediaType = "application/octet-stream",
                            schema = @Schema(type = "string", format = "binary")
                    )),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "주택 또는 첨부파일을 찾을 수 없는 경우입니다.")
    })
    public ResponseEntity<byte[]> attachmentContent(
            @Parameter(description = "조회할 공급주택 ID입니다.", example = "1") @PathVariable Long housingId,
            @Parameter(description = "조회할 첨부파일 ID입니다.", example = "10") @PathVariable Long attachmentId
    ) {
        return inline(assetService.getAttachment(housingId, attachmentId));
    }

    @GetMapping("/{housingId}/media/{mediaId}/content")
    @Operation(
            summary = "주택 이미지 내용 조회",
            description = "해당 주택에 속한 평면도·배치도·위치도 등의 이미지 파일을 반환합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "이미지 파일을 정상적으로 반환합니다.",
                    content = @Content(
                            mediaType = "application/octet-stream",
                            schema = @Schema(type = "string", format = "binary")
                    )),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "주택 또는 이미지를 찾을 수 없는 경우입니다.")
    })
    public ResponseEntity<byte[]> mediaContent(
            @Parameter(description = "조회할 공급주택 ID입니다.", example = "1") @PathVariable Long housingId,
            @Parameter(description = "조회할 주택 미디어 ID입니다.", example = "20") @PathVariable Long mediaId
    ) {
        return inline(assetService.getMedia(housingId, mediaId));
    }

    private ResponseEntity<byte[]> inline(com.zipick.notice.service.MapHousingAsset asset) {
        return ResponseEntity.ok()
                .contentType(asset.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(asset.fileName(), java.nio.charset.StandardCharsets.UTF_8)
                                .build().toString())
                .body(asset.content());
    }
}
