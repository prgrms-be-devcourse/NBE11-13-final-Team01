package com.zipick.notice.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.zipick.notice.support.RegionNormalizer.Region;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 행정구역 표준화 단위 테스트. DB·외부 API 없이 돈다.
 *
 * <p>실제 로컬 DB에서 발견된 값을 그대로 사례로 쓴다.
 */
class RegionNormalizerTest {

    @Nested
    @DisplayName("시도 표준화")
    class ProvinceCanonicalization {

        @DisplayName("축약형·구 명칭을 현재 공식 명칭으로 바꾼다")
        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
                "서울, 서울특별시", "서울시, 서울특별시", "경기, 경기도",
                "부산, 부산광역시", "대구, 대구광역시", "인천, 인천광역시",
                "대전, 대전광역시", "울산, 울산광역시", "세종, 세종특별자치시",
                "충북, 충청북도", "충남, 충청남도", "경북, 경상북도", "경남, 경상남도",
                "강원, 강원특별자치도", "강원도, 강원특별자치도",
                "전북, 전북특별자치도", "전라북도, 전북특별자치도",
                "제주, 제주특별자치도", "제주도, 제주특별자치도",
        })
        void mapsAliases(String raw, String canonical) {
            assertThat(RegionNormalizer.canonicalProvince(raw)).isEqualTo(canonical);
        }

        @DisplayName("행정구역 통합을 반영한다")
        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
                "광주광역시, 전남광주통합특별시",
                "전라남도, 전남광주통합특별시",
                "전남, 전남광주통합특별시",
        })
        void mapsMergedProvinces(String raw, String canonical) {
            assertThat(RegionNormalizer.canonicalProvince(raw)).isEqualTo(canonical);
        }

        @Test
        @DisplayName("'광주'는 경기도 광주시와 구분할 수 없어 매핑하지 않는다")
        void doesNotGuessAmbiguousAbbreviation() {
            assertThat(RegionNormalizer.canonicalProvince("광주")).isNull();
        }

        @Test
        @DisplayName("이미 공식 명칭이면 그대로 둔다")
        void keepsCanonical() {
            assertThat(RegionNormalizer.canonicalProvince("서울특별시")).isEqualTo("서울특별시");
            assertThat(RegionNormalizer.canonicalProvince("전남광주통합특별시")).isEqualTo("전남광주통합특별시");
            assertThat(RegionNormalizer.canonicalProvince("경기도")).isEqualTo("경기도");
        }
    }

    @Nested
    @DisplayName("구조가 밀린 데이터 복구")
    class ShiftedRegion {

        @Test
        @DisplayName("시도 자리에 시가 들어오면 한 칸 되돌린다 (도로명 district 는 버린다)")
        void repairsCityInProvinceSlot() {
            // 실데이터: housing 428~430 (경상북도개발공사)
            Region r = RegionNormalizer.normalize("영주시", "대동로34번길",
                    "영주시 대동로34번길 23-3", "영주시대동로34번길23-3");

            assertThat(r.province()).isNull();          // 주소에 시도가 없어 확정할 수 없다
            assertThat(r.district()).isEqualTo("영주시");
        }

        @Test
        @DisplayName("시도 자리에 군이 들어오면 한 칸 되돌린다 (읍 district 는 버린다)")
        void repairsCountyInProvinceSlot() {
            // 실데이터: housing 432~434
            Region r = RegionNormalizer.normalize("칠곡군", "왜관읍",
                    "칠곡군 왜관읍 달오 2길 76", "칠곡군왜관읍달오2길76");

            assertThat(r.province()).isNull();
            assertThat(r.district()).isEqualTo("칠곡군");
        }

        @Test
        @DisplayName("주소에 시도가 있으면 되돌린 뒤 시도까지 복구한다")
        void recoversProvinceFromAddressAfterShift() {
            Region r = RegionNormalizer.normalize("영주시", "대동로34번길",
                    "경상북도 영주시 대동로34번길 23-3", null);

            assertThat(r.province()).isEqualTo("경상북도");
            assertThat(r.district()).isEqualTo("영주시");
        }

        @DisplayName("⚠️ 접미사 없는 정상 축약형을 구조 오류로 오판하지 않는다")
        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({
                "서울, 강북구, 서울특별시, 강북구",
                "경기, 김포시, 경기도, 김포시",
                "충남, 아산시, 충청남도, 아산시",
        })
        void doesNotTreatKnownAliasAsShifted(String province, String district,
                                             String expectedProvince, String expectedDistrict) {
            Region r = RegionNormalizer.normalize(province, district, district + " 어딘가로 1", null);

            assertThat(r.province()).isEqualTo(expectedProvince);
            assertThat(r.district()).isEqualTo(expectedDistrict);
        }
    }

    @Nested
    @DisplayName("일반구 정밀도")
    class GeneralGu {

        @DisplayName("주소에 일반구가 있으면 '시 + 구'까지 복구한다")
        @ParameterizedTest(name = "{2} → {3}")
        @MethodSource("com.zipick.notice.support.RegionNormalizerTest#generalGuCases")
        void recoversGeneralGu(String province, String district, String address, String expected) {
            Region r = RegionNormalizer.normalize(province, district, address, null);

            assertThat(r.district()).isEqualTo(expected);
        }

        @Test
        @DisplayName("주소에 구가 없으면 추측하지 않고 시까지만 둔다")
        void doesNotGuessGeneralGu() {
            Region r = RegionNormalizer.normalize("충청남도", "천안시", "충청남도 천안시 두정동 1503", null);

            assertThat(r.district()).isEqualTo("천안시");
        }

        @Test
        @DisplayName("normalizedAddress 를 주소보다 먼저 믿는다")
        void prefersNormalizedAddress() {
            Region r = RegionNormalizer.normalize("충청남도", "천안시",
                    "충청남도 천안시 두정동 1503",           // 원문에는 구가 없고
                    "충청남도 천안시 서북구 두정로 1");       // Kakao 결과에는 있다

            assertThat(r.district()).isEqualTo("천안시 서북구");
        }

        @Test
        @DisplayName("자치구(광역시 아래 구)는 시가 아니므로 합치지 않는다")
        void keepsAutonomousGu() {
            Region r = RegionNormalizer.normalize("서울특별시", "강남구", "서울특별시 강남구 개포로 621", null);

            assertThat(r.district()).isEqualTo("강남구");
        }
    }

    @Nested
    @DisplayName("정상값 보존과 미상 처리")
    class KeepAndDrop {

        @DisplayName("현재 공식값으로 확인된 신설 구는 그대로 둔다")
        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({"인천광역시, 서해구", "인천광역시, 검단구", "경기도, 화성시 병점구", "경기도, 화성시 동탄구"})
        void keepsNewOfficialDistricts(String province, String district) {
            Region r = RegionNormalizer.normalize(province, district, province + " " + district + " 어딘가로 1", null);

            assertThat(r.province()).isEqualTo(province);
            assertThat(r.district()).isEqualTo(district);
        }

        @Test
        @DisplayName("행정구역이 아닌 개발지구 이름은 지역 미상으로 둔다")
        void dropsNonAdministrativeArea() {
            // 실데이터: housing 438~439. 좌표도 없어 복구 근거가 전혀 없다
            Region r = RegionNormalizer.normalize(null, "도청이전신도시",
                    "도청이전신도시 12개동 1", "도청이전신도시12개동1");

            assertThat(r.province()).isNull();
            assertThat(r.district()).isNull();
        }

        @Test
        @DisplayName("읍·면·도로명이 시군구 자리에 있으면 버린다")
        void dropsSubDistrictTokens() {
            assertThat(RegionNormalizer.normalize("경상북도", "왜관읍", "경상북도 칠곡군 왜관읍 달오3길 26", null).district())
                    .isEqualTo("칠곡군");   // 주소에서 제대로 된 시군구를 다시 찾는다
            assertThat(RegionNormalizer.normalize("경상북도", "대동로34번길", "경상북도 어딘가", null).district())
                    .isNull();
        }

        @Test
        @DisplayName("모르는 시도는 함부로 지우지 않는다 (새 행정구역명일 수 있다)")
        void keepsUnknownProvince() {
            Region r = RegionNormalizer.normalize("새로운특별시", "어떤구", "새로운특별시 어떤구 1로 2", null);

            assertThat(r.province()).isEqualTo("새로운특별시");
            assertThat(r.district()).isEqualTo("어떤구");
        }

        @DisplayName("null·공백은 null 로")
        @ParameterizedTest
        @CsvSource(value = {"NULL|NULL", "'   '|'  '"}, delimiter = '|', nullValues = "NULL")
        void handlesBlank(String province, String district) {
            Region r = RegionNormalizer.normalize(province, district, null, null);

            assertThat(r.province()).isNull();
            assertThat(r.district()).isNull();
        }

        @Test
        @DisplayName("시도가 비면 주소에서 찾고, 찾은 값도 다시 표준화한다")
        void recoversProvinceFromAddressAndCanonicalizes() {
            Region r = RegionNormalizer.normalize(null, "강북구", "서울 강북구 도봉로 228", null);

            assertThat(r.province()).isEqualTo("서울특별시");
            assertThat(r.district()).isEqualTo("강북구");
        }
    }

    @DisplayName("멱등성 — 다시 돌려도 결과가 같다 (backfill 재실행 안전)")
    @ParameterizedTest(name = "[{index}] {0} / {1}")
    @MethodSource("idempotencyCases")
    void isIdempotent(String province, String district, String address, String normalizedAddress) {
        Region once = RegionNormalizer.normalize(province, district, address, normalizedAddress);
        Region twice = RegionNormalizer.normalize(once.province(), once.district(), address, normalizedAddress);

        assertThat(twice).isEqualTo(once);
    }

    static List<Object[]> generalGuCases() {
        return List.of(
                new Object[]{"충청남도", "천안시", "충청남도 천안시 서북구 두정동 1503", "천안시 서북구"},
                new Object[]{"전북특별자치도", "전주시", "전북특별자치도 전주시 완산구 홍산로 158", "전주시 완산구"},
                new Object[]{"경기도", "수원시", "경기도 수원시 영통구 봉영로 1", "수원시 영통구"},
                new Object[]{"경기도", "성남시", "경기도 성남시 분당구 성남대로54번길 3", "성남시 분당구"},
                new Object[]{"경기도", "용인시", "경기도 용인시 처인구 고진로27번길 23", "용인시 처인구"},
                new Object[]{"경기도", "부천시", "경기도 부천시 소사구 소사로148번길 58-9", "부천시 소사구"},
                new Object[]{"경기도", "안산시", "경기도 안산시 상록구 본오로7길 40", "안산시 상록구"},
                new Object[]{"경기도", "안양시", "경기도 안양시 만안구 현충로 12", "안양시 만안구"},
                new Object[]{"경상북도", "포항시", "경상북도 포항시 북구 우창동로 157", "포항시 북구"},
                new Object[]{"충청북도", "청주시", "충청북도 청주시 서원구 구룡산로52번길 40", "청주시 서원구"}
        );
    }

    static List<Object[]> idempotencyCases() {
        return List.of(
                new Object[]{"서울", "강북구", "강북구 미아동 194-2", "서울 강북구 도봉로 228"},
                new Object[]{"경기", "김포시", "김포시 양촌읍 양곡리 490", "경기 김포시 양촌읍 양곡4로 164-1"},
                new Object[]{"전라북도", "전주시", "전라북도 전주시 완산구 홍산로 158", null},
                new Object[]{"광주광역시", "광산구", "광주광역시 광산구 무진대로 246-7", null},
                new Object[]{"영주시", "대동로34번길", "영주시 대동로34번길 23-3", "영주시대동로34번길23-3"},
                new Object[]{"칠곡군", "왜관읍", "칠곡군 왜관읍 달오 2길 76", null},
                new Object[]{null, "도청이전신도시", "도청이전신도시 12개동 1", "도청이전신도시12개동1"},
                new Object[]{"인천광역시", "서해구", "인천광역시 서해구 도요지로42번길 45", null},
                new Object[]{"경기도", "부천시", "경기도 부천시 소사구 소사로148번길 58-9", null}
        );
    }
}
