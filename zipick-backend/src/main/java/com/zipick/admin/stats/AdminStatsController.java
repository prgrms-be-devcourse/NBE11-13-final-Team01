package com.zipick.admin.stats;

import com.zipick.admin.AdminQueryRepository;
import com.zipick.admin.aidoc.AiDocumentIssueRepository;
import com.zipick.admin.collection.CollectionRun;
import com.zipick.admin.collection.CollectionRunRepository;
import com.zipick.applicationguide.repository.ApplicationGuideRepository;
import com.zipick.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 운영자(ROLE_ADMIN)만. 가입·온보딩·관심 지역·AI 사용량 같은 운영 지표. 서버 자원은 Grafana 가 맡는다 */
@Tag(name = "운영: 지표")
@RestController
@RequestMapping("/api/v1/admin/stats")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminStatsController {

    private final AdminQueryRepository query;
    private final ApplicationGuideRepository guides;
    private final AiDocumentIssueRepository issues;
    private final CollectionRunRepository runs;
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    public record RegionCount(String key, long value) {}

    public record Stats(
            Map<String, Object> users,
            List<RegionCount> topRegions,
            Map<String, Object> ai,
            Map<String, Object> collection
    ) {
    }

    @Operation(summary = "운영 지표 한 번에")
    @GetMapping
    @Transactional(readOnly = true)
    public ApiResponse<Stats> stats() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> users = new LinkedHashMap<>();
        long total = query.countUsers();
        long onboarded = query.countOnboardedUsers();
        users.put("total", total);
        users.put("newLast7Days", query.countUsersCreatedAfter(now.minusDays(7)));
        users.put("newLast30Days", query.countUsersCreatedAfter(now.minusDays(30)));
        users.put("onboarded", onboarded);
        users.put("onboardingRate", total == 0 ? 0 : Math.round(onboarded * 1000.0 / total) / 10.0);
        users.put("notificationEnabled", query.countUsersWithNotificationEnabled());
        users.put("marketingConsented", query.countMarketingConsented());
        users.put("interestNotices", query.countInterestNotices());

        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("guidesLast7Days", guides.countByGeneratedAtAfter(now.minusDays(7)));
        ai.put("guidesLast30Days", guides.countByGeneratedAtAfter(now.minusDays(30)));
        ai.put("openDocumentIssues", issues.countByAcknowledgedFalse());
        ai.put("normalizationJobsByStatus", query.countNormalizationJobsByStatus());

        Map<String, Object> collection = new LinkedHashMap<>();
        List<CollectionRun> recent = runs.findAllByStartedAtAfterOrderByStartedAtDesc(Instant.now().minus(Duration.ofDays(7)));
        collection.put("runsLast7Days", recent.size());
        collection.put("failedLast7Days", recent.stream().filter(r -> r.getStatus() == CollectionRun.Status.FAILED).count());
        collection.put("infraSavedLast7Days", recent.stream().mapToLong(this::infraSaved).sum());
        collection.put("createdNoticesLast7Days", recent.stream().mapToLong(r -> number(summary(r).get("createdNotices"))).sum());

        return ApiResponse.ok(new Stats(users, topRegions(10), ai, collection));
    }

    /** 희망 지역(user_preferences.regions JSON) 상위 N. 회원 수가 적어 전부 읽어 센다 */
    private List<RegionCount> topRegions(int limit) {
        Map<String, Long> counts = new TreeMap<>();
        for (String json : query.allPreferenceRegions()) {
            try {
                List<Map<String, Object>> regions = JSON_MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
                for (Map<String, Object> region : regions) {
                    Object code = region.get("code");
                    if (code != null) counts.merge(String.valueOf(code), 1L, Long::sum);
                }
            } catch (Exception ignored) {
                // 옛 형식이거나 깨진 값은 집계에서 뺀다
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()))
                .limit(limit).map(e -> new RegionCount(e.getKey(), e.getValue())).toList();
    }

    private Map<String, Object> summary(CollectionRun run) {
        if (run.getSummary() == null) return Map.of();
        try {
            return JSON_MAPPER.readValue(run.getSummary(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private long infraSaved(CollectionRun run) {
        Object infra = summary(run).get("infra");
        if (infra instanceof Map<?, ?> m) return number(m.get("saved"));
        return 0;
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0;
    }
}
