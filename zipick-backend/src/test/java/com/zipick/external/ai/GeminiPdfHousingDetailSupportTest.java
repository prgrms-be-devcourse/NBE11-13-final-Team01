package com.zipick.external.ai;

import com.zipick.external.housingdetail.DetailedHousingUnit;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GeminiPdfHousingDetailSupportTest {
    private final GeminiPdfHousingDetailSupport support = new GeminiPdfHousingDetailSupport(
            mock(GeminiPdfParserProperties.class), mock(GeminiPdfHousingExtractor.class));

    @Test
    void 주소만_찾은_기본파서의_개수는_공급행_힌트가_아니다() {
        var first = mock(DetailedHousingUnit.class);
        var second = mock(DetailedHousingUnit.class);
        assertThat(support.verifiedRowCount(List.of(first, second))).isNull();
        when(first.sourceRowNumber()).thenReturn(1);
        when(second.sourceRowNumber()).thenReturn(2);
        assertThat(support.verifiedRowCount(List.of(first, second))).isEqualTo(2);
        when(second.sourceRowNumber()).thenReturn(4);
        assertThat(support.verifiedRowCount(List.of(first, second))).isNull();
    }

    @Test
    void 주택_없는_문서의_빈응답은_AI_주택_반영으로_표시하지_않는다() {
        var properties = mock(GeminiPdfParserProperties.class);
        var extractor = mock(GeminiPdfHousingExtractor.class);
        when(properties.isConfigured()).thenReturn(true);
        when(extractor.extract(anyList())).thenReturn(new GeminiPdfExtractionResult(
                List.of(), true, "", 0, 1, false, GeminiTokenUsage.empty(),
                List.of(), List.of(), List.of()));
        var result = new GeminiPdfHousingDetailSupport(properties, extractor)
                .supplementWithStatus(new byte[]{1}, "doc", "LH", "공고", "공고.pdf", List.of());
        assertThat(result.units()).isEmpty();
        assertThat(result.aiApplied()).isFalse();
        assertThat(result.aiComplete()).isTrue();
    }
}
