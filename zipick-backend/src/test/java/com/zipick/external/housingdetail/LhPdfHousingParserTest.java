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
}
