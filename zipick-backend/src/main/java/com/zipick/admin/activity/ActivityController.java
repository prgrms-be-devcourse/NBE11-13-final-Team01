package com.zipick.admin.activity;

import com.zipick.global.auth.CurrentUser;
import com.zipick.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 프론트가 화면 진입·클릭을 묶어 보내는 곳. 비로그인도 받는다(permitAll).
 * 응답은 저장 건수뿐이라 실패해도 화면에 아무 영향이 없다. 프론트는 sendBeacon 으로 보내므로 본문은 작게 유지한다.
 */
@Tag(name = "행동 기록", description = "화면 진입·클릭 수집 (운영자 화면에서 조회)")
@RestController
@RequestMapping("/api/v1/activity")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityService service;

    public record Event(String type, @Size(max = 500) String path, @Size(max = 200) String label,
                        @Size(max = 200) String target, Instant at) {}

    public record Batch(@Size(max = 64) String anonymousId, @Valid @Size(max = ActivityService.MAX_BATCH) List<Event> events) {}

    @Operation(summary = "행동 이벤트 묶음 저장 (최대 50건)")
    @SecurityRequirements
    @PostMapping
    public ApiResponse<Map<String, Integer>> record(@CurrentUser(required = false) Long userId,
                                                    @Valid @RequestBody Batch batch,
                                                    HttpServletRequest request) {
        List<ActivityService.EventIn> events = batch.events() == null ? List.of() : batch.events().stream()
                .map(e -> new ActivityService.EventIn(e.type(), e.path(), e.label(), e.target(), e.at())).toList();
        return ApiResponse.ok(Map.of("saved", service.record(userId, batch.anonymousId(), request, events)));
    }
}
