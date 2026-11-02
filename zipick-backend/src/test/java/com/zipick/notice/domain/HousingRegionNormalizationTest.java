package com.zipick.notice.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 지역 값을 쓰는 세 경로가 **모두** 같은 표준화를 거치는지 본다.
 *
 * <p>수집기마다 따로 정규화하지 않고 엔티티 한 곳에서 처리하기로 한 설계의 회귀 방지 테스트다.
 */
class HousingRegionNormalizationTest {

    private static final Instant SEEN = Instant.parse("2026-10-28T00:00:00Z");

    private Housing housing(String address) {
        Notice notice = new Notice("LH", "n1", "공고", NoticeStatus.OPEN);
        return new Housing(notice, "k1", "단지", address);
    }

    @Test
    @DisplayName("synchronize: 수집기가 넘긴 구 명칭과 1토큰 시군구가 표준화된다")
    void synchronizeNormalizes() {
        Housing h = housing("전라북도 전주시 완산구 홍산로 158");

        h.synchronize(null, "단지", "전라북도", "전주시",
                "전라북도 전주시 완산구 홍산로 158", null, null, null, null, null, null, null, null, SEEN);

        assertThat(h.getProvinceName()).isEqualTo("전북특별자치도");
        assertThat(h.getDistrictName()).isEqualTo("전주시 완산구");
    }

    @Test
    @DisplayName("synchronize: 행정구역 통합이 반영된다")
    void synchronizeAppliesMergedProvince() {
        Housing h = housing("광주광역시 광산구 무진대로 246-7");

        h.synchronize(null, "단지", "광주광역시", "광산구",
                "광주광역시 광산구 무진대로 246-7", null, null, null, null, null, null, null, null, SEEN);

        assertThat(h.getProvinceName()).isEqualTo("전남광주통합특별시");
        assertThat(h.getDistrictName()).isEqualTo("광산구");
    }

    @Test
    @DisplayName("enrichAddress: Kakao 축약형 시도도 표준명으로 저장된다")
    void enrichAddressCanonicalizesKakaoShortForm() {
        Housing h = housing("강북구 미아동 194-2");

        // Kakao region_1depth_name 은 "서울"처럼 축약형이다
        h.enrichAddress("서울 강북구 도봉로 228", "서울", "강북구", "미아동", "도봉로", "단지",
                new BigDecimal("37.6"), new BigDecimal("127.0"));

        assertThat(h.getProvinceName()).isEqualTo("서울특별시");
        assertThat(h.getDistrictName()).isEqualTo("강북구");
    }

    @Test
    @DisplayName("⚠️ enrichAddress: 이미 들어 있던 비표준 값도 교정된다 (fillWhenBlank 한계 보완)")
    void enrichAddressFixesExistingNonCanonicalValue() {
        Housing h = housing("김포시 양촌읍 양곡리 490");
        h.synchronize(null, "단지", "경기", "김포시", "김포시 양촌읍 양곡리 490",
                null, null, null, null, null, null, null, null, SEEN);
        assertThat(h.getProvinceName()).isEqualTo("경기도");   // synchronize 단계에서 이미 교정

        // Kakao 보정은 fillWhenBlank 라 기존 값을 덮지 않지만, 표준화는 다시 돈다
        h.enrichAddress("경기 김포시 양촌읍 양곡4로 164-1", "경기", "김포시", "양촌읍", "양곡4로", null,
                new BigDecimal("37.6"), new BigDecimal("126.6"));

        assertThat(h.getProvinceName()).isEqualTo("경기도");
        assertThat(h.getDistrictName()).isEqualTo("김포시");
    }

    @Test
    @DisplayName("applyAiNormalization: AI 검수 결과도 같은 표준화를 거친다")
    void applyAiNormalizationNormalizes() {
        Housing h = housing("충청남도 천안시 서북구 두정동 1503");

        h.applyAiNormalization("단지", "충남", "천안시",
                "충청남도 천안시 서북구 두정동 1503", null, null);

        assertThat(h.getProvinceName()).isEqualTo("충청남도");
        assertThat(h.getDistrictName()).isEqualTo("천안시 서북구");
    }

    @Test
    @DisplayName("시도 자리에 시가 밀려 들어온 데이터가 복구된다")
    void repairsShiftedRegion() {
        Housing h = housing("영주시 대동로34번길 23-3");

        h.synchronize(null, "더 포레", "영주시", "대동로34번길", "영주시 대동로34번길 23-3",
                null, null, null, null, null, null, null, null, SEEN);

        assertThat(h.getProvinceName()).isNull();          // 주소에 시도가 없어 확정 불가
        assertThat(h.getDistrictName()).isEqualTo("영주시");

        // 이후 Kakao 보정이 시도를 채우면 표준명으로 들어간다
        h.enrichAddress("경북 영주시 대동로34번길 23-3", "경북", "영주시", null, "대동로34번길", null,
                new BigDecimal("36.81"), new BigDecimal("128.60"));

        assertThat(h.getProvinceName()).isEqualTo("경상북도");
        assertThat(h.getDistrictName()).isEqualTo("영주시");
    }

    @Test
    @DisplayName("행정구역이 아닌 주소는 지역 미상으로 남는다")
    void leavesUnknownRegionEmpty() {
        Housing h = housing("도청이전신도시 12개동 1");

        h.synchronize(null, null, null, "도청이전신도시", "도청이전신도시 12개동 1",
                "도청이전신도시12개동1", null, null, null, null, null, null, null, SEEN);

        assertThat(h.getProvinceName()).isNull();
        assertThat(h.getDistrictName()).isNull();
    }

    @Test
    @DisplayName("이미 표준값이면 건드리지 않는다")
    void keepsCanonicalValues() {
        Housing h = housing("인천광역시 서해구 도요지로42번길 45");

        h.synchronize(null, "범한주택6차", "인천광역시", "서해구", "인천광역시 서해구 도요지로42번길 45",
                null, null, null, null, null, null, null, null, SEEN);

        assertThat(h.getProvinceName()).isEqualTo("인천광역시");
        assertThat(h.getDistrictName()).isEqualTo("서해구");
    }

    /**
     * 기본 수집 → 좌표·주소 보완 → AI 상세 수집 순서를 그대로 재현한다.
     *
     * <p>실제로 깨졌던 경로다. AI 상세 수집이 같은 주택을 다시 {@code synchronize} 하면서
     * 지오코딩이 넣어 둔 표준 주소("경북 영주시 …")를 중복 비교용 축약형("영주시대동로34번길23-3")으로
     * 덮어썼고, 그 바람에 시도를 복구할 근거가 사라져 {@code province_name} 이 비었다.
     * 주소 자체에 시도가 없는 경상북도개발공사(GBDC) 공고에서만 드러났다.
     */
    @Test
    @DisplayName("AI 상세 수집이 다시 저장해도 보완으로 확정한 시도가 유지된다 — 영주시")
    void keepsGeocodedProvinceWhenAiDetailResyncs() {
        Housing h = housing("영주시 대동로34번길 23-3");

        // ① 기본 수집: 주소에 시도가 없어 파서는 시군구만 넘긴다
        h.synchronize(null, "더 포레", null, "영주시", "영주시 대동로34번길 23-3",
                null, null, null, null, null, null, null, null, SEEN);
        assertThat(h.getProvinceName()).isNull();

        // ② 좌표·주소 보완: 카카오 표준 주소가 시도를 확정한다
        h.enrichAddress("경북 영주시 대동로34번길 23-3", "경북", "영주시", null, "대동로34번길", null,
                new BigDecimal("36.81"), new BigDecimal("128.60"));
        assertThat(h.getProvinceName()).isEqualTo("경상북도");

        // ③ AI 상세 수집: 시도 자리에 시가 밀려 들어온 값으로 같은 주택을 다시 저장한다
        h.synchronize(null, "더 포레", "영주시", "대동로34번길", "영주시 대동로34번길 23-3",
                null, null, null, null, null, null, null, null, SEEN);

        assertThat(h.getProvinceName()).isEqualTo("경상북도");
        assertThat(h.getDistrictName()).isEqualTo("영주시");
        // 복구 근거가 그대로 남아 있어야 한다
        assertThat(h.getNormalizedAddress()).isEqualTo("경북 영주시 대동로34번길 23-3");
    }

    @Test
    @DisplayName("AI 상세 수집이 다시 저장해도 보완으로 확정한 시도가 유지된다 — 칠곡군")
    void keepsGeocodedProvinceWhenAiDetailResyncsForCounty() {
        Housing h = housing("칠곡군 석적읍 중리3길 10");

        h.synchronize(null, "중리마을", null, "칠곡군", "칠곡군 석적읍 중리3길 10",
                null, null, null, null, null, null, null, null, SEEN);
        h.enrichAddress("경북 칠곡군 석적읍 중리3길 10", "경북", "칠곡군", "석적읍", "중리3길", null,
                new BigDecimal("36.03"), new BigDecimal("128.44"));
        assertThat(h.getProvinceName()).isEqualTo("경상북도");

        h.synchronize(null, "중리마을", "칠곡군", "석적읍", "칠곡군 석적읍 중리3길 10",
                null, null, null, null, null, null, null, null, SEEN);

        assertThat(h.getProvinceName()).isEqualTo("경상북도");
        assertThat(h.getDistrictName()).isEqualTo("칠곡군");
    }

    @Test
    @DisplayName("같은 수집을 여러 번 반복해도 지역 값이 흔들리지 않는다")
    void repeatedSyncIsStable() {
        Housing h = housing("영주시 대동로34번길 23-3");
        h.synchronize(null, "더 포레", null, "영주시", "영주시 대동로34번길 23-3",
                null, null, null, null, null, null, null, null, SEEN);
        h.enrichAddress("경북 영주시 대동로34번길 23-3", "경북", "영주시", null, "대동로34번길", null,
                new BigDecimal("36.81"), new BigDecimal("128.60"));

        for (int i = 0; i < 3; i++) {
            h.synchronize(null, "더 포레", "영주시", "대동로34번길", "영주시 대동로34번길 23-3",
                    null, null, null, null, null, null, null, null, SEEN);
            assertThat(h.getProvinceName()).isEqualTo("경상북도");
            assertThat(h.getDistrictName()).isEqualTo("영주시");
        }
    }

    /**
     * 기존 값을 무조건 지키는 것이 아니다. 뒤 수집이 <b>확정적인</b> 지역을 넘기면 그대로 바뀌어야 한다.
     * 잘못 들어간 지역을 재수집으로 고칠 수 있어야 하기 때문이다.
     */
    @Test
    @DisplayName("뒤 수집이 확정적인 시도를 넘기면 기존 값을 고친다")
    void laterCollectionCanCorrectRegion() {
        Housing h = housing("대구광역시 달서구 갈밭남로 17");
        h.synchronize(null, "단지", null, "달서구", "대구광역시 달서구 갈밭남로 17",
                null, null, null, null, null, null, null, null, SEEN);
        h.enrichAddress("경북 칠곡군 석적읍 중리3길 10", "경북", "칠곡군", null, null, null,
                new BigDecimal("36.03"), new BigDecimal("128.44"));
        assertThat(h.getProvinceName()).isEqualTo("대구광역시");   // 수집값이 이미 있으면 보완은 덮지 않는다

        h.synchronize(null, "단지", "경상남도", "창원시 성산구", "경상남도 창원시 성산구 중앙대로 100",
                null, null, null, null, null, null, null, null, SEEN);

        assertThat(h.getProvinceName()).isEqualTo("경상남도");
        assertThat(h.getDistrictName()).isEqualTo("창원시 성산구");
    }
}
