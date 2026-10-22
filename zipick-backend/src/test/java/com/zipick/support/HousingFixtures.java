package com.zipick.support;

import com.zipick.notice.domain.Agency;
import com.zipick.notice.domain.Housing;
import com.zipick.notice.domain.HousingSupply;
import com.zipick.notice.domain.Notice;
import com.zipick.notice.domain.NoticeStatus;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * PostgreSQL 통합 테스트용 데이터 생성기.
 *
 * <p>테스트마다 필요한 모양이 달라 빌더처럼 쓴다. 저장 순서(기관 → 공고 → 주택 → 호실)를 지켜야
 * 외래키가 맞는다. 금액은 엔티티와 같은 원 단위다(필터는 만 원을 받아 10,000 을 곱한다).
 */
public final class HousingFixtures {

    private final EntityManager em;
    private int sequence = 0;

    public HousingFixtures(EntityManager em) {
        this.em = em;
    }

    public Agency agency(String code, String name) {
        Agency agency = new Agency(code, name, "https://example.test/" + code);
        em.persist(agency);
        return agency;
    }

    public Notice notice(Agency agency, NoticeStatus status, String title, String noticeType) {
        Notice notice = new Notice("TEST", "n-" + (++sequence), title, status);
        notice.assignAgency(agency);
        notice.synchronize(agency, null, title, noticeType, null, null, status, null, null,
                null, null, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-15T14:30:00Z"),
                "https://example.test/notice", null, null, null, "{}", Instant.now());
        em.persist(notice);
        return notice;
    }

    /** 좌표가 있는 활성 주택 */
    public Housing housing(Notice notice, String name, String province, String district) {
        return housing(notice, name, province, district, new BigDecimal("37.5000000"), new BigDecimal("127.0000000"), true);
    }

    /** 좌표가 없는 주택 (아직 주소 좌표 변환이 안 된 상태) */
    public Housing housingWithoutCoordinates(Notice notice, String name) {
        return housing(notice, name, "서울특별시", "강남구", null, null, true);
    }

    /** 비활성 주택 (수집에서 반복 누락되어 꺼진 상태) */
    public Housing inactiveHousing(Notice notice, String name) {
        return housing(notice, name, "서울특별시", "강남구", new BigDecimal("37.5"), new BigDecimal("127.0"), false);
    }

    private Housing housing(Notice notice, String name, String province, String district,
                            BigDecimal latitude, BigDecimal longitude, boolean active) {
        Housing housing = new Housing(notice, "h-" + (++sequence), name, province + " " + district + " 1");
        housing.enrichAddress(province + " " + district + " 1", province, district, "동", "로",
                name, latitude, longitude);
        if (!active) {
            deactivate(housing::markMissing);
        }
        em.persist(housing);
        return housing;
    }

    /**
     * 공급 호실.
     *
     * @param depositManwon 보증금(만 원). null 이면 값 없음
     * @param rentManwon    월 임대료(만 원). null 이면 값 없음
     * @param areaSquare    전용면적(㎡). null 이면 값 없음
     * @param housingType   주택 유형 원본 문자열. null 이면 값 없음
     */
    public HousingSupply supply(Housing housing, Integer depositManwon, Integer rentManwon,
                                String areaSquare, String housingType, boolean active) {
        HousingSupply supply = new HousingSupply(housing, "s-" + (++sequence), 1L);
        supply.synchronize(1L, housingType, "국민임대", "101동", "101호", 1, 2, 1,
                areaSquare == null ? null : new BigDecimal(areaSquare), 2, 2,
                depositManwon == null ? null : depositManwon * 10_000L, null, null, null,
                rentManwon == null ? null : rentManwon * 10_000L,
                "공급중", null, null, true, true, 10, false, 0, "{}", Instant.now());
        if (!active) {
            deactivate(supply::markMissing);
        }
        em.persist(supply);
        return supply;
    }

    /**
     * 수집에서 연속으로 누락됐을 때와 같은 상태로 만든다.
     * markMissing 은 호출마다 누락 횟수를 1 씩 올리고 임계치(MISSING_THRESHOLD = 2)에 닿아야 꺼지며,
     * 같은 날짜로 두 번 부르면 무시하므로 날짜를 달리해 두 번 부른다.
     */
    private void deactivate(MissingMarker marker) {
        marker.markMissing(java.time.LocalDate.of(2026, 1, 1), 2);
        marker.markMissing(java.time.LocalDate.of(2026, 1, 2), 2);
    }

    @FunctionalInterface
    private interface MissingMarker {
        boolean markMissing(java.time.LocalDate checkedOn, int threshold);
    }

    public void flush() {
        em.flush();
        em.clear();
    }
}
