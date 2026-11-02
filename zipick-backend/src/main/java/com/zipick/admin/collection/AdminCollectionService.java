package com.zipick.admin.collection;

import com.zipick.admin.AdminQueryRepository;
import com.zipick.admin.aidoc.AiDocumentIssue;
import com.zipick.admin.aidoc.AiDocumentIssueRepository;
import com.zipick.admin.dto.PageResponse;
import com.zipick.external.ai.AiNormalizationJob;
import com.zipick.external.ai.AiNormalizationJobRepository;
import com.zipick.external.ai.AiNormalizationStatus;
import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 운영자 화면 "수집 현황": 실행 기록·수동 실행 요청·AI 미완료 문서·정규화 작업 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminCollectionService {

    /** 이 시간보다 오래 RUNNING 이면 프로세스가 죽은 것으로 보고 "실행 중" 에서 제외한다 (AI 수집 실측 최대 ~2.5h) */
    private static final Duration STALE_RUNNING = Duration.ofHours(4);

    private final CollectionRunRepository runs;
    private final CollectionRequestRepository requests;
    private final AiDocumentIssueRepository issues;
    private final AiNormalizationJobRepository normalizationJobs;
    private final AdminQueryRepository query;
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    // ---- 현황 ----

    public record RunResponse(Long id, CollectionJobType jobType, String trigger, Long requestId, String status,
                              Instant startedAt, Instant endedAt, Long durationSeconds,
                              Map<String, Object> summary, String errorMessage) {
    }

    public record RequestResponse(Long id, CollectionJobType jobType, String status, Long requestedBy, String note,
                                  Instant createdAt, Instant startedAt, Instant finishedAt, String resultMessage) {
    }

    public record Overview(
            Map<String, RunResponse> lastRuns,
            List<RunResponse> running,
            List<RequestResponse> openRequests,
            Map<String, Object> data
    ) {
    }

    public Overview overview() {
        Map<String, RunResponse> last = new LinkedHashMap<>();
        for (CollectionJobType type : EnumSet.of(CollectionJobType.BASIC, CollectionJobType.AI, CollectionJobType.INFRA)) {
            runs.findFirstByJobTypeOrderByStartedAtDesc(type).ifPresent(r -> last.put(type.name(), toResponse(r)));
        }
        Instant staleBefore = Instant.now().minus(STALE_RUNNING);
        List<RunResponse> running = runs.findAllByStatus(CollectionRun.Status.RUNNING).stream()
                .filter(r -> r.getStartedAt().isAfter(staleBefore))
                .map(this::toResponse).toList();
        List<RequestResponse> open = requests.findAllByStatusIn(List.of(CollectionRequest.Status.PENDING, CollectionRequest.Status.RUNNING))
                .stream().map(this::toResponse).toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("housings", query.countHousings());
        data.put("activeHousings", query.countActiveHousings());
        data.put("housingsWithoutCoordinates", query.countActiveHousingsWithoutCoordinates());
        data.put("openHousings", query.countOpenHousings());
        data.put("openHousingsWithoutInfra", query.countOpenHousingsWithoutInfra());
        data.put("infraSnapshots", query.countInfraSnapshots());
        data.put("noticesByStatus", query.countNoticesByStatus());
        data.put("normalizationJobsByStatus", query.countNormalizationJobsByStatus());
        data.put("openAiDocumentIssues", issues.countByAcknowledgedFalse());
        return new Overview(last, running, open, data);
    }

    public PageResponse<RunResponse> runs(CollectionJobType jobType, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<CollectionRun> result = jobType == null
                ? runs.findAllByOrderByStartedAtDesc(pageable)
                : runs.findAllByJobTypeOrderByStartedAtDesc(jobType, pageable);
        return PageResponse.of(result, this::toResponse);
    }

    /** 프로세스가 죽어 RUNNING 으로 남은 기록을 실패로 닫는다 */
    @Transactional
    public RunResponse abandon(Long runId) {
        CollectionRun run = runs.findById(runId).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (run.getStatus() != CollectionRun.Status.RUNNING) throw new BusinessException(ErrorCode.DATA_CONFLICT, "실행 중인 기록만 닫을 수 있습니다.");
        run.abandon(Instant.now());
        return toResponse(run);
    }

    // ---- 수동 실행 요청 ----

    public PageResponse<RequestResponse> requests(int page, int size) {
        return PageResponse.of(requests.findAllByOrderByIdDesc(PageRequest.of(page - 1, size)), this::toResponse);
    }

    @Transactional
    public RequestResponse request(Long adminId, CollectionJobType jobType, String note) {
        // 수집기는 같은 DB 를 다루므로 한 번에 하나만. 실행기(쉘)도 flock 으로 막지만 화면에서 먼저 거른다
        if (requests.existsByStatusIn(List.of(CollectionRequest.Status.PENDING, CollectionRequest.Status.RUNNING))) {
            throw new BusinessException(ErrorCode.COLLECTION_ALREADY_REQUESTED);
        }
        return toResponse(requests.save(new CollectionRequest(jobType, adminId, note)));
    }

    @Transactional
    public RequestResponse cancel(Long requestId) {
        CollectionRequest request = requests.findById(requestId).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (request.getStatus() != CollectionRequest.Status.PENDING) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "대기 중인 요청만 취소할 수 있습니다. 이미 시작된 실행은 끝날 때까지 기다려 주세요.");
        }
        request.cancel();
        return toResponse(request);
    }

    // ---- AI 미완료 문서 ----

    public record IssueResponse(Long id, String documentKey, String agency, String noticeTitle, String fileName,
                                String reason, Integer expectedUnits, Integer parsedUnits, String detail,
                                int occurrences, Instant firstSeenAt, Instant lastSeenAt,
                                boolean acknowledged, String adminNote) {
    }

    public PageResponse<IssueResponse> issues(boolean acknowledged, String reason, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<AiDocumentIssue> result = reason == null || reason.isBlank()
                ? issues.findAllByAcknowledgedOrderByLastSeenAtDesc(acknowledged, pageable)
                : issues.findAllByAcknowledgedAndReasonOrderByLastSeenAtDesc(acknowledged, reason, pageable);
        return PageResponse.of(result, this::toResponse);
    }

    @Transactional
    public IssueResponse acknowledge(Long adminId, Long issueId, String note) {
        AiDocumentIssue issue = issues.findById(issueId).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        issue.acknowledge(adminId, note);
        return toResponse(issue);
    }

    @Transactional
    public IssueResponse reopen(Long issueId) {
        AiDocumentIssue issue = issues.findById(issueId).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        issue.reopen();
        return toResponse(issue);
    }

    // ---- AI 정규화 작업 ----

    public record NormalizationJobResponse(Long id, String source, String documentId, String noticeTitle, String status,
                                           int attemptCount, Instant nextRetryAt, String lastError, Instant normalizedAt,
                                           String normalizerVersion) {
    }

    public PageResponse<NormalizationJobResponse> normalizationJobs(AiNormalizationStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id"));
        Page<AiNormalizationJob> result = status == null
                ? normalizationJobs.findAll(pageable)
                : normalizationJobs.findAllByStatus(status, pageable);
        return PageResponse.of(result, j -> new NormalizationJobResponse(
                j.getId(), j.getSource(), j.getDocumentId(), j.getNoticeTitle(),
                j.getStatus() == null ? null : j.getStatus().name(),
                j.getAttemptCount(), j.getNextRetryAt(), j.getLastError(), j.getNormalizedAt(), j.getNormalizerVersion()
        ));
    }

    /** 다음 AI 실행에서 다시 시도하도록 되돌린다 */
    @Transactional
    public NormalizationJobResponse requeue(Long jobId) {
        AiNormalizationJob job = normalizationJobs.findById(jobId).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        job.requeueForNormalizerVersion(job.getNormalizerVersion());
        return new NormalizationJobResponse(
                job.getId(), job.getSource(), job.getDocumentId(), job.getNoticeTitle(),
                job.getStatus() == null ? null : job.getStatus().name(),
                job.getAttemptCount(), job.getNextRetryAt(), job.getLastError(), job.getNormalizedAt(), job.getNormalizerVersion()
        );
    }

    // ---- 변환 ----

    private RunResponse toResponse(CollectionRun r) {
        Long duration = r.getEndedAt() == null ? null : Duration.between(r.getStartedAt(), r.getEndedAt()).toSeconds();
        return new RunResponse(r.getId(), r.getJobType(), r.getTrigger(), r.getRequestId(), r.getStatus().name(),
                r.getStartedAt(), r.getEndedAt(), duration, parseSummary(r.getSummary()), r.getErrorMessage());
    }

    private RequestResponse toResponse(CollectionRequest r) {
        Instant created = r.getCreatedAt() == null ? null : r.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant();
        return new RequestResponse(r.getId(), r.getJobType(), r.getStatus().name(), r.getRequestedBy(), r.getNote(),
                created, r.getStartedAt(), r.getFinishedAt(), r.getResultMessage());
    }

    private IssueResponse toResponse(AiDocumentIssue i) {
        return new IssueResponse(i.getId(), i.getDocumentKey(), i.getAgency(), i.getNoticeTitle(), i.getFileName(),
                i.getReason(), i.getExpectedUnits(), i.getParsedUnits(), i.getDetail(), i.getOccurrences(),
                i.getFirstSeenAt(), i.getLastSeenAt(), i.isAcknowledged(), i.getAdminNote());
    }

    private Map<String, Object> parseSummary(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JSON_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of("raw", json);
        }
    }
}
