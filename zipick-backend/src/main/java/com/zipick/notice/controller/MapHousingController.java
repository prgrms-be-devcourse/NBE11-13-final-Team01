package com.zipick.notice.controller;

import com.zipick.global.response.ApiResponse;
import com.zipick.notice.dto.MapHousingDetailResponse;
import com.zipick.notice.dto.MapHousingListResponse;
import com.zipick.notice.service.MapHousingAssetService;
import com.zipick.notice.service.MapHousingQueryService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/map/housings")
public class MapHousingController {

    private final MapHousingQueryService service;
    private final MapHousingAssetService assetService;

    public MapHousingController(MapHousingQueryService service, MapHousingAssetService assetService) {
        this.service = service;
        this.assetService = assetService;
    }

    @GetMapping
    public ApiResponse<MapHousingListResponse> list() {
        return ApiResponse.ok(service.findOpenHousings());
    }

    @GetMapping("/{housingId}")
    public ApiResponse<MapHousingDetailResponse> detail(@PathVariable Long housingId) {
        return ApiResponse.ok(service.findDetail(housingId));
    }

    @GetMapping("/{housingId}/attachments/{attachmentId}/content")
    public ResponseEntity<byte[]> attachmentContent(
            @PathVariable Long housingId,
            @PathVariable Long attachmentId
    ) {
        return inline(assetService.getAttachment(housingId, attachmentId));
    }

    @GetMapping("/{housingId}/media/{mediaId}/content")
    public ResponseEntity<byte[]> mediaContent(
            @PathVariable Long housingId,
            @PathVariable Long mediaId
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
