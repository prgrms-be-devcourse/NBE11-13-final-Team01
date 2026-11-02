package com.zipick.external.housingdetail;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class LhPdfHousingParserTest {

    private final LhPdfHousingParser parser = new LhPdfHousingParser();

    @Test
    void 실제_전세임대와_칠곡_공고의_문의처를_주택으로_읽지_않는다() throws Exception {
        for (String id : List.of("68868379", "68868733", "68869337", "68869885", "GBDC-3efc4e699f734d8e311beaa2")) {
            var file = java.nio.file.Path.of("tmp", "ai-investigation", id + ".pdf");
            org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(file));
            assertThat(parser.parse(java.nio.file.Files.readAllBytes(file), new LhAttachment(id, "공고문.pdf")))
                    .as(id).isEmpty();
        }
    }

    @Test
    void 주택을_나중에_물색하거나_별도안내하는_공고의_문의처를_제외한다() {
        String office = "충청북도 청주시 서원구 구룡산로52번길 40";
        String lease = "입주대상자로 선정된 이후 주택을 물색하여야 하며\n" + office;
        String deferred = "공급대상 주택 내역은 동호지정 및 계약체결 시 별도 안내 예정\n" + office;
        assertThat(parser.parseText(lease, new LhAttachment("1", "전세임대공고.pdf"))).isEmpty();
        assertThat(parser.parseText(deferred, new LhAttachment("2", "공고문.hwpx"))).isEmpty();
        assertThat(parser.parseText(lease, new LhAttachment("3", "공급주택목록.pdf"))).hasSize(1);
    }

    @Test
    void 줄바꿈된_신청장소와_공사_안내주소는_주택주소로_수집하지_않는다() {
        String text = """
                신청장소 LH 경기북부지역본부
                주소 경기도 의정부시 산단로76번길 116 1층
                ⊙ 주소 : 경기도 의정부시 산단로76번길 116
                1600-1004 LH홈페이지 https://lh.or.kr
                LH경기남부지역본부
                장소 1층 대강당
                (경기도 성남시 분당구 성남대로 54번길 3)
                건설위치 : 경기도 구리시 갈매동 123-4
                """;
        assertThat(parser.parseText(text, new LhAttachment("1", "공고.pdf")))
                .extracting(DetailedHousingUnit::address).containsExactly("경기도 구리시 갈매동 123-4");
    }

    @Test
    void 단지_동수와_세대수는_지번주소가_아니다() {
        var units = parser.parseText("도청이전신도시 12개동 1,196세대", new LhAttachment("1", "공고문.pdf"));
        assertThat(units).isEmpty();
    }

    @Test
    void 도로명과_건물번호가_붙은_주소를_중간에서_자르지_않는다() {
        var units = parser.parseText("건물 위치 : 서울특별시 노원구 동일로 242길103 (상계동 1002-1)",
                new LhAttachment("1", "공고.pdf"));
        assertThat(units).singleElement().satisfies(unit ->
                assertThat(unit.address()).startsWith("서울특별시 노원구 동일로 242길103"));
    }

    @Test
    void 번길_번호를_건물번호로_잘라내지_않는다() {
        var units = parser.parseText("경기도 성남시 분당구 성남대로 54번길 3", new LhAttachment("1", "공고문.pdf"));
        assertThat(units).extracting(DetailedHousingUnit::address)
                .containsExactly("경기도 성남시 분당구 성남대로 54번길 3");
    }

    @Test
    void 텍스트_PDF의_도로명주소와_지번주소를_찾는다() {
        String text = """
                1 강원특별자치도 춘천시 효자문길 38 (효자동) 202호 59.40
                2 경기도 성남시 분당구 판교동 123-4 301호
                """;

        List<DetailedHousingUnit> units = parser.parseText(text, new LhAttachment("20", "공급주택목록.pdf"));

        assertThat(units).extracting(DetailedHousingUnit::address)
                .containsExactly(
                        "강원특별자치도 춘천시 효자문길 38 (효자동)",
                        "경기도 성남시 분당구 판교동 123-4"
                );
    }

    @Test
    void 일반구가_있는_도시는_시군구를_구까지_읽는다() {
        // 예전에는 토큰 하나만 읽어 "천안시"·"전주시"까지만 남았고, 같은 도시가 두 지역으로 갈렸다.
        String text = """
                1 충청남도 천안시 서북구 오성1길 12-10
                2 전북특별자치도 전주시 완산구 홍산로 158
                3 경기도 부천시 소사구 소사로148번길 58-9
                4 경상북도 포항시 북구 우창동로 157
                """;

        List<DetailedHousingUnit> units = parser.parseText(text, new LhAttachment("30", "공급주택목록.pdf"));

        assertThat(units).extracting(DetailedHousingUnit::provinceName, DetailedHousingUnit::districtName)
                .containsExactly(
                        tuple("충청남도", "천안시 서북구"),
                        tuple("전북특별자치도", "전주시 완산구"),
                        tuple("경기도", "부천시 소사구"),
                        tuple("경상북도", "포항시 북구")
                );
    }

    @Test
    void 일반구가_없으면_시군구만_읽고_구를_만들지_않는다() {
        String text = """
                1 강원특별자치도 춘천시 효자문길 38 (효자동)
                2 서울특별시 강남구 선릉로121길 12
                3 경상북도 칠곡군 왜관읍 달오3길 26-10
                """;

        List<DetailedHousingUnit> units = parser.parseText(text, new LhAttachment("31", "공급주택목록.pdf"));

        assertThat(units).extracting(DetailedHousingUnit::provinceName, DetailedHousingUnit::districtName)
                .containsExactly(
                        tuple("강원특별자치도", "춘천시"),
                        tuple("서울특별시", "강남구"),
                        tuple("경상북도", "칠곡군")
                );
    }

    @Test
    void 숫자가_포함된_도로명을_건물번호와_분리한다() {
        String text = """
                전북특별자치도 김제시 하동1길 127(하동,김제하동주공아파트)
                부산광역시 부산진구 백양산로53번길 125
                서울특별시 강남구 선릉로121길 12
                충청북도 청주시 서원구 구룡산로52번길 40
                """;

        List<DetailedHousingUnit> units = parser.parseText(text, new LhAttachment("21", "주택목록.pdf"));

        assertThat(units).extracting(DetailedHousingUnit::address)
                .containsExactly(
                        "전북특별자치도 김제시 하동1길 127(하동,김제하동주공아파트)",
                        "부산광역시 부산진구 백양산로53번길 125",
                        "서울특별시 강남구 선릉로121길 12",
                        "충청북도 청주시 서원구 구룡산로52번길 40"
                );
    }

    @Test
    void 신청장소와_지역본부와_견본주택_주소는_주택으로_수집하지_않는다() {
        String text = """
                신청 장소: 충청남도 아산시 배방읍 공원로 52 LH 아산사업단
                LH 서울지역본부 서울특별시 강남구 선릉로121길 12
                견본주택: 대전광역시 동구 계족로 32
                공급주택: 전북특별자치도 김제시 하동1길 127
                """;

        List<DetailedHousingUnit> units = parser.parseText(text, new LhAttachment("22", "입주자모집공고문.pdf"));

        assertThat(units).extracting(DetailedHousingUnit::address)
                .containsExactly("전북특별자치도 김제시 하동1길 127");
    }
}
