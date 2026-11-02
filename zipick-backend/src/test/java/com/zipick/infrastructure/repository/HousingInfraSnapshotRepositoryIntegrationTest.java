package com.zipick.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.zipick.housing.dto.HouseFilter;
import com.zipick.infrastructure.domain.HousingInfraSnapshot;
import com.zipick.notice.domain.Agency;
import com.zipick.notice.domain.Housing;
import com.zipick.notice.domain.NoticeStatus;
import com.zipick.support.HousingFixtures;
import com.zipick.support.PostgresRepositoryTestBase;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * 수집 대상 조회를 실제 PostgreSQL 에서 검증한다.
 *
 * <p>이 쿼리는 한 번의 실행에서 대상이 없어질 때까지 반복 호출된다. 그래서 Mock 으로는 보이지 않는
 * <b>커서 페이징이 실제로 같은 주택을 다시 돌려주지 않는지</b>와 네 가지 재수집 트리거가
 * 진짜 DB 에서 어떻게 판정되는지를 여기서 본다.
 */
class HousingInfraSnapshotRepositoryIntegrationTest extends PostgresRepositoryTestBase {

    private static final String SIGNATURE = "SW8:1500|CS2:1500";
    private static final BigDecimal LATITUDE = new BigDecimal("37.5000000");
    private static final BigDecimal LONGITUDE = new BigDecimal("127.0000000");
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    private static final Instant TTL_THRESHOLD = NOW.minus(30, ChronoUnit.DAYS);
    private static final Pageable UNLIMITED = PageRequest.of(0, 1000);

    @Autowired
    private HousingInfraSnapshotRepository repository;

    @Autowired
    private EntityManager em;

    private HousingFixtures fixtures;
    private Agency lh;

    @BeforeEach
    void setUp() {
        fixtures = new HousingFixtures(em);
        lh = fixtures.agency("LH", "한국토지주택공사");
    }

    private List<Long> targetIds(long afterHousingId, Pageable pageable) {
        fixtures.flush();
        return repository.findCollectionTargets(
                HouseFilter.OPEN_STATUSES, TTL_THRESHOLD, SIGNATURE, afterHousingId, pageable
        ).stream().map(Housing::getId).toList();
    }

    private List<Long> allTargets() {
        return targetIds(0L, UNLIMITED);
    }

    private Housing housing(NoticeStatus status, String name) {
        return fixtures.housing(fixtures.notice(lh, status, name, null), name, "서울특별시", "강남구");
    }

    private void snapshot(Housing housing, String signature, BigDecimal latitude, BigDecimal longitude,
                          Instant collectedAt) {
        em.persist(new HousingInfraSnapshot(
                housing.getId(), signature, latitude, longitude, "{}", collectedAt));
    }

    /** 지금 정책·좌표로 방금 수집한, 다시 수집할 필요가 없는 스냅샷 */
    private void freshSnapshot(Housing housing) {
        snapshot(housing, SIGNATURE, housing.getLatitude(), housing.getLongitude(), NOW);
    }

    @Test
    @DisplayName("스냅샷이 없는 주택은 대상이고, 유효한 스냅샷이 있으면 제외한다")
    void selectsHousingsWithoutValidSnapshot() {
        Housing pending = housing(NoticeStatus.OPEN, "미수집");
        Housing collected = housing(NoticeStatus.OPEN, "수집완료");
        fixtures.flush();
        freshSnapshot(collected);

        assertThat(allTargets()).containsExactly(pending.getId());
    }

    @Test
    @DisplayName("TTL 만료·정책 변경·좌표 변경 스냅샷은 다시 대상이 된다")
    void reselectsStaleSnapshots() {
        Housing expired = housing(NoticeStatus.OPEN, "TTL만료");
        Housing policyChanged = housing(NoticeStatus.OPEN, "정책변경");
        Housing moved = housing(NoticeStatus.OPEN, "좌표변경");
        Housing intact = housing(NoticeStatus.OPEN, "유효");
        fixtures.flush();

        snapshot(expired, SIGNATURE, LATITUDE, LONGITUDE, TTL_THRESHOLD.minusSeconds(1));
        snapshot(policyChanged, "SW8:1000", LATITUDE, LONGITUDE, NOW);
        snapshot(moved, SIGNATURE, new BigDecimal("37.6000000"), LONGITUDE, NOW);
        freshSnapshot(intact);

        assertThat(allTargets())
                .containsExactlyInAnyOrder(expired.getId(), policyChanged.getId(), moved.getId());
    }

    @Test
    @DisplayName("공고 예정과 접수 중만 대상이고 마감·취소·비활성·좌표 없음은 제외한다")
    void selectsOpenAndUpcomingOnly() {
        Housing upcoming = housing(NoticeStatus.UPCOMING, "공고예정");
        Housing open = housing(NoticeStatus.OPEN, "접수중");
        housing(NoticeStatus.CLOSED, "마감");
        housing(NoticeStatus.CANCELLED, "취소");
        fixtures.inactiveHousing(fixtures.notice(lh, NoticeStatus.OPEN, "비활성공고", null), "비활성");
        fixtures.housingWithoutCoordinates(fixtures.notice(lh, NoticeStatus.OPEN, "좌표없음공고", null), "좌표없음");

        assertThat(allTargets()).containsExactlyInAnyOrder(upcoming.getId(), open.getId());
    }

    @Test
    @DisplayName("커서보다 큰 id 만 돌려주므로 이미 처리한 주택이 다시 나오지 않는다")
    void pagesForwardWithCursor() {
        Housing first = housing(NoticeStatus.OPEN, "첫째");
        Housing second = housing(NoticeStatus.OPEN, "둘째");
        Housing third = housing(NoticeStatus.OPEN, "셋째");
        Pageable batch = PageRequest.of(0, 2);

        assertThat(targetIds(0L, batch)).containsExactly(first.getId(), second.getId());
        // 저장에 실패해 스냅샷이 없는 상태 그대로여도 커서가 넘어가면 다시 선택되지 않는다
        assertThat(targetIds(second.getId(), batch)).containsExactly(third.getId());
        assertThat(targetIds(third.getId(), batch)).isEmpty();
    }

    @Test
    @DisplayName("커서 페이징을 반복하면 대상을 중복 없이 모두 한 번씩 가져온다")
    void drainsEveryTargetExactlyOnce() {
        List<Long> expected = List.of(
                housing(NoticeStatus.OPEN, "A").getId(),
                housing(NoticeStatus.UPCOMING, "B").getId(),
                housing(NoticeStatus.OPEN, "C").getId(),
                housing(NoticeStatus.OPEN, "D").getId(),
                housing(NoticeStatus.UPCOMING, "E").getId()
        );
        Pageable batch = PageRequest.of(0, 2);

        List<Long> drained = new ArrayList<>();
        long cursor = 0L;
        // 스냅샷을 하나도 저장하지 않는다(= 전부 실패한 상황). 커서만으로 끝나야 한다.
        for (int guard = 0; guard < 10; guard++) {
            List<Long> page = targetIds(cursor, batch);
            if (page.isEmpty()) {
                break;
            }
            drained.addAll(page);
            cursor = page.getLast();
        }

        assertThat(drained).containsExactlyElementsOf(expected);
    }
}
