package com.zipick.external.ai;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AiNormalizationJobTest {

    @Test
    void 실패한_정규화는_입력을_보존한채_재시도하고_같은입력은_상태를_초기화하지않는다() {
        AiNormalizationJob job = job("hash-1", "V1", "payload-1");
        Instant failedAt = Instant.parse("2026-10-26T00:00:00Z");

        job.markRetry("503 HIGH DEMAND", failedAt);
        boolean changed = job.enqueue("공고", "hash-1", "V1", "payload-1");

        assertThat(changed).isFalse();
        assertThat(job.getStatus()).isEqualTo(AiNormalizationStatus.RETRY);
        assertThat(job.getAttemptCount()).isEqualTo(1);
        assertThat(job.getNextRetryAt()).isEqualTo(failedAt.plusSeconds(15 * 60));
        assertThat(job.getInputPayload()).isEqualTo("payload-1");
    }

    @Test
    void 입력이나_정규화버전이_바뀌면_완료작업도_다시_대기시킨다() {
        AiNormalizationJob job = job("hash-1", "V1", "payload-1");
        job.markSuccess(Instant.parse("2026-10-26T00:00:00Z"));

        assertThat(job.enqueue("수정 공고", "hash-2", "V1", "payload-2")).isTrue();
        assertThat(job.getStatus()).isEqualTo(AiNormalizationStatus.PENDING);
        assertThat(job.getAttemptCount()).isZero();
        assertThat(job.getNormalizedAt()).isNull();

        job.markSuccess(Instant.parse("2026-10-26T01:00:00Z"));
        job.requeueForNormalizerVersion("V2");
        assertThat(job.getStatus()).isEqualTo(AiNormalizationStatus.PENDING);
        assertThat(job.getNormalizerVersion()).isEqualTo("V2");
        assertThat(job.getInputPayload()).isEqualTo("payload-2");
    }

    private AiNormalizationJob job(String hash, String version, String payload) {
        return new AiNormalizationJob("LH", "LH:notice-1", "공고", hash, version, payload);
    }
}
