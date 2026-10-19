package com.zipick.external.sh;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShHousingDetailSourceTest {

    private final ShHousingDetailSource source = new ShHousingDetailSource(
            null, null, null, null, null, null
    );

    @Test
    void readsAttachmentMetadataFromDetailHtml() {
        String html = """
                <script>
                initParam.downList = [{"brdId":"GS0401","seq":"310650","fileSeq":"4",
                "fileSize":911179,"oriFileNm":"주택목록(상세)_신혼.pdf","fileTp":"A"}];
                </script>
                """;

        assertThat(source.findAttachments(html)).containsExactly(
                new ShAttachment("GS0401", "310650", "4", 911179L, "주택목록(상세)_신혼.pdf", "A")
        );
    }

    @Test
    void prioritizesDetailedThenListThenSummaryThenAnnouncement() {
        List<ShAttachment> candidates = source.housingCandidates(List.of(
                attachment("1", "[공고문] 입주자 모집공고.pdf"),
                attachment("2", "주택목록(요약).pdf"),
                attachment("3", "주택목록.xlsx"),
                attachment("4", "주택목록(상세).pdf"),
                attachment("5", "개인정보 동의서.pdf")
        ));

        assertThat(candidates).extracting(ShAttachment::fileSeq)
                .containsExactly("4", "3", "2", "1");
    }

    @Test
    void prefersLatestRevisionAndSpreadsheetWithinSameCategory() {
        List<ShAttachment> candidates = source.housingCandidates(List.of(
                attachment("1", "주택목록(상세).pdf"),
                attachment("2", "주택목록(상세)_수정.pdf"),
                attachment("3", "주택목록(상세)_수정2.xlsx")
        ));

        assertThat(candidates).extracting(ShAttachment::fileSeq)
                .containsExactly("3", "2", "1");
    }

    private ShAttachment attachment(String fileSeq, String name) {
        return new ShAttachment("GS0401", "100", fileSeq, 100L, name, "A");
    }
}
