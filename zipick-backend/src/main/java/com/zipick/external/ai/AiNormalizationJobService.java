package com.zipick.external.ai;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** 정규화 입력과 재시도 상태를 PDF 파싱 성공 이력과 별도로 관리합니다. */
@Service
public class AiNormalizationJobService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AiNormalizationJobRepository repository;

    public AiNormalizationJobService(AiNormalizationJobRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void enqueue(List<GeminiNormalizationDocument> documents) {
        for (GeminiNormalizationDocument document : documents) {
            String payload = JSON.writeValueAsString(document);
            String inputHash = sha256(payload);
            Optional<AiNormalizationJob> existing = repository
                    .findBySourceAndDocumentId(document.agency(), document.documentId());
            AiNormalizationJob job;
            if (existing.isPresent()) {
                job = existing.get();
                job.enqueue(document.noticeTitle(), inputHash,
                        GeminiDbNormalizationReviewer.PROMPT_VERSION, payload);
            } else {
                job = new AiNormalizationJob(
                        document.agency(), document.documentId(), document.noticeTitle(), inputHash,
                        GeminiDbNormalizationReviewer.PROMPT_VERSION, payload);
            }
            repository.save(job);
        }
    }

    @Transactional
    public List<AiNormalizationJob> findDue() {
        repository.findAllByNormalizerVersionNot(GeminiDbNormalizationReviewer.PROMPT_VERSION)
                .forEach(job -> job.requeueForNormalizerVersion(
                        GeminiDbNormalizationReviewer.PROMPT_VERSION));
        return repository.findDue(
                List.of(AiNormalizationStatus.PENDING, AiNormalizationStatus.RETRY), Instant.now());
    }

    public GeminiNormalizationDocument readDocument(AiNormalizationJob job) {
        return JSON.readValue(job.getInputPayload(), GeminiNormalizationDocument.class);
    }

    @Transactional
    public void markSuccess(List<Long> jobIds) {
        Instant now = Instant.now();
        repository.findAllById(jobIds).forEach(job -> job.markSuccess(now));
    }

    @Transactional
    public void markRetry(List<Long> jobIds, String reason) {
        Instant now = Instant.now();
        repository.findAllById(jobIds).forEach(job -> job.markRetry(reason, now));
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("AI 정규화 입력 해시를 만들지 못했습니다.", exception);
        }
    }
}
