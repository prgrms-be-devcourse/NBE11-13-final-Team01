package com.zipick.external.housingdetail;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
