package com.zipick.infrastructure.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class HousingInfraSnapshotTest {

    private static final String SIGNATURE = "MART:MT1:1000|SUBWAY:SW8:1500";
    private static final BigDecimal LATITUDE = new BigDecimal("37.5756000");
    private static final BigDecimal LONGITUDE = new BigDecimal("127.0250000");

    @Test
    @DisplayName("TTL 안이고 정책·좌표가 그대로면 유효한 캐시다")
    void validWhenNothingChanged() {
        HousingInfraSnapshot snapshot = snapshot(Instant.now());

        assertThat(snapshot.isValid(SIGNATURE, LATITUDE, LONGITUDE, ttlThreshold())).isTrue();
    }

    @Test
    @DisplayName("TTL 이 지나면 재수집 대상이다")
    void invalidWhenExpired() {
        HousingInfraSnapshot snapshot = snapshot(Instant.now().minus(Duration.ofDays(31)));

        assertThat(snapshot.isValid(SIGNATURE, LATITUDE, LONGITUDE, ttlThreshold())).isFalse();
    }

    @Test
    @DisplayName("수집 정책이 바뀌면 재수집 대상이다")
    void invalidWhenPolicyChanged() {
        HousingInfraSnapshot snapshot = snapshot(Instant.now());

        assertThat(snapshot.isValid("MART:MT1:2000|SUBWAY:SW8:1500", LATITUDE, LONGITUDE, ttlThreshold()))
                .isFalse();
    }

    @Test
    @DisplayName("주택 좌표가 바뀌면 재수집 대상이다")
    void invalidWhenCoordinateChanged() {
        HousingInfraSnapshot snapshot = snapshot(Instant.now());

        assertThat(snapshot.isValid(SIGNATURE, new BigDecimal("37.6000000"), LONGITUDE, ttlThreshold()))
                .isFalse();
    }

    @Test
    @DisplayName("소수점 표기만 다른 같은 좌표는 변경으로 보지 않는다")
    void treatsScaleDifferenceAsSameCoordinate() {
        HousingInfraSnapshot snapshot = snapshot(Instant.now());

        assertThat(snapshot.isValid(SIGNATURE, new BigDecimal("37.5756"), new BigDecimal("127.025"), ttlThreshold()))
                .isTrue();
    }

    @Test
    @DisplayName("수집 맥락 비교는 TTL 과 무관하다 — 추천은 만료된 스냅샷도 쓴다")
    void collectionContextIgnoresTtl() {
        HousingInfraSnapshot expired = snapshot(Instant.now().minus(Duration.ofDays(90)));

        assertThat(expired.isValid(SIGNATURE, LATITUDE, LONGITUDE, ttlThreshold())).isFalse();
        assertThat(expired.matchesCollectionContext(SIGNATURE, LATITUDE, LONGITUDE)).isTrue();
    }

    @Test
    @DisplayName("정책이나 좌표가 다르면 수집 맥락도 맞지 않는다")
    void collectionContextRejectsChangedPolicyOrCoordinate() {
        HousingInfraSnapshot snapshot = snapshot(Instant.now());

        assertThat(snapshot.matchesCollectionContext("MART:MT1:2000|SUBWAY:SW8:1500", LATITUDE, LONGITUDE))
                .isFalse();
        assertThat(snapshot.matchesCollectionContext(SIGNATURE, new BigDecimal("37.6000000"), LONGITUDE))
                .isFalse();
    }

    private static Instant ttlThreshold() {
        return Instant.now().minus(Duration.ofDays(30));
    }

    private static HousingInfraSnapshot snapshot(Instant collectedAt) {
        return new HousingInfraSnapshot(1L, SIGNATURE, LATITUDE, LONGITUDE, "{}", collectedAt);
    }
}
