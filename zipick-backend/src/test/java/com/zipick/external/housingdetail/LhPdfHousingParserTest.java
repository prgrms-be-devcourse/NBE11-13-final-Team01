package com.zipick.external.housingdetail;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class LhPdfHousingParserTest {

    private final LhPdfHousingParser parser = new LhPdfHousingParser();

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
