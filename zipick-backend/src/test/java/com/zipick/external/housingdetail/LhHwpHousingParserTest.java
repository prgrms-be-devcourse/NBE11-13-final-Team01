package com.zipick.external.housingdetail;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.assertj.core.api.Assertions.assertThat;

class LhHwpHousingParserTest {
    @Test
    void 실제_전세_질의응답에서_월세가_없다는_문장을_확인한다() throws Exception {
        var path = java.nio.file.Path.of("..", "tmp", "lh-69028529.hwpx");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(path));
        var parser = new LhHwpHousingParser(new LhPdfHousingParser());
        String text = parser.extractText(java.nio.file.Files.readAllBytes(path), "질의응답.hwpx");
        assertThat(LhHousingDetailSource.isExplicitPureJeonse("든든전세주택 입주자 모집", text)).isTrue();
    }
    @Test
    void 실제_청년전세임대_공고의_지역본부는_주택이_아니다() throws Exception {
        var path = java.nio.file.Path.of("tmp", "ai-investigation", "68869881.hwpx");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(path));
        var parser = new LhHwpHousingParser(new LhPdfHousingParser());
        assertThat(parser.parse(java.nio.file.Files.readAllBytes(path), new LhAttachment("68869881", "공고문.hwpx"))).isEmpty();
    }

    @Test
    void hwpx_본문에서_소재지를_추출한다() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("Contents/section0.xml"));
            zip.write("<sec xmlns:hp='urn:test'><hp:p><hp:t>소재지 : 제주특별자치도 서귀포시 신중로 46</hp:t></hp:p></sec>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        var parser = new LhHwpHousingParser(new LhPdfHousingParser());
        var units = parser.parse(bytes.toByteArray(), new LhAttachment("1", "공고문.hwpx"));
        assertThat(units).extracting(DetailedHousingUnit::address)
                .containsExactly("제주특별자치도 서귀포시 신중로 46");
    }
}
