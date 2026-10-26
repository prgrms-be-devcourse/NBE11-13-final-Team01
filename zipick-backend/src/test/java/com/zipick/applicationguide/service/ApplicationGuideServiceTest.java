package com.zipick.applicationguide.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zipick.applicationguide.client.GeminiClient;
import com.zipick.applicationguide.client.GeminiCompletion;
import com.zipick.applicationguide.client.GeminiException;
import com.zipick.applicationguide.config.ApplicationGuideProperties;
import com.zipick.applicationguide.config.GeminiProperties;
import com.zipick.applicationguide.document.ApplicationSectionReducer;
import com.zipick.applicationguide.document.ReducedDocument;
import com.zipick.applicationguide.document.SectionCategory;
import com.zipick.applicationguide.document.DocumentFormat;
import com.zipick.applicationguide.document.ExtractedDocument;
import com.zipick.applicationguide.document.GuideDocumentException;
import com.zipick.applicationguide.document.LocatedDocument;
import com.zipick.applicationguide.document.NoticeDocumentLocator;
import com.zipick.applicationguide.document.NoticeDocumentTextExtractor;
import com.zipick.applicationguide.dto.ApplicationGuideResponse;
import com.zipick.applicationguide.dto.GuideRequestContext;
import com.zipick.applicationguide.dto.GuideResult;
import com.zipick.applicationguide.dto.GuideSourceRef;
import com.zipick.applicationguide.dto.GuideStatus;
import com.zipick.applicationguide.dto.GuideUnavailableReason;
import com.zipick.applicationguide.dto.GuideUserContext;
import com.zipick.user.domain.StudentStatus;
import java.util.Set;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 가이드 조회·생성 흐름. 여기서 확인하려는 핵심은 <b>언제 Gemini 를 부르지 않는가</b>다.
 * 무료 사용량을 쓰는 기능이라 "안 부르는 경로"가 기능의 절반이다.
 */
@ExtendWith(MockitoExtension.class)
class ApplicationGuideServiceTest {

    private static final LocalDateTime PROFILE_AT = LocalDateTime.of(2026, 10, 20, 9, 0);
    private static final ApplicationGuideResponse.NoticeHeader HEADER =
            new ApplicationGuideResponse.NoticeHeader(10L, "2026 청년매입임대 모집", "LH", "https://apply.lh.or.kr/10");

    @Mock ApplicationGuidePersistenceService persistence;
    @Mock NoticeDocumentLocator locator;
    @Mock NoticeDocumentTextExtractor extractor;
    @Mock ApplicationSectionReducer reducer;
    @Mock GeminiClient geminiClient;

    private ApplicationGuideService service(GeminiProperties gemini, ApplicationGuideProperties props) {
        return new ApplicationGuideService(persistence, locator, extractor, reducer, geminiClient, props, gemini);
    }

    private ApplicationGuideService service() {
        return service(configuredGemini(), defaultProperties());
    }

    private static GeminiProperties configuredGemini() {
        return new GeminiProperties("real-key", null);
    }

    private static ApplicationGuideProperties defaultProperties() {
        // 0 = 일일 상한 없음 (상한 자체를 검증하는 테스트만 아래에서 숫자를 준다)
        return new ApplicationGuideProperties("flash-lite", null, null, 0, 0, 0, 0, 0);
    }

    private static GuideRequestContext context(GuideRequestContext.SavedGuide saved) {
        return new GuideRequestContext(HEADER, "LH", null, null, List.of(),
                new GuideUserContext(24, false, 1, true, StudentStatus.UNIVERSITY), PROFILE_AT, saved);
    }

    private static GuideRequestContext.SavedGuide saved(boolean reusable) {
        GuideResult result = new GuideResult("요약", List.of(), List.of(), List.of());
        return new GuideRequestContext.SavedGuide(result,
                Map.of("S1", GuideSourceRef.ofPage("모집공고.pdf", 24)),
                "flash-lite", LocalDateTime.of(2026, 10, 25, 9, 0), reusable);
    }

    @Test
    void 조회는_저장된_결과가_있으면_그대로_돌려준다() {
        when(persistence.load(1L, 10L)).thenReturn(context(saved(true)));

        ApplicationGuideResponse response = service().find(1L, 10L);

        assertThat(response.status()).isEqualTo(GuideStatus.READY);
        assertThat(response.cached()).isTrue();
        assertThat(response.guide().summary()).isEqualTo("요약");
        assertThat(response.sources()).containsKey("S1");
    }

    @Test
    void 조회는_만든_적이_없으면_NOT_GENERATED_를_돌려준다() {
        when(persistence.load(1L, 10L)).thenReturn(context(null));

        ApplicationGuideResponse response = service().find(1L, 10L);

        assertThat(response.status()).isEqualTo(GuideStatus.NOT_GENERATED);
        assertThat(response.noticeDetailUrl()).isEqualTo("https://apply.lh.or.kr/10");
    }

    @Test
    void 조회는_기준이_바뀐_예전_결과를_현재_결과처럼_보여주지_않는다() {
        // 사용자가 '대학생' 을 해제했는데 "현재 대학생이므로..." 로 시작하는 예전 가이드를
        // 그대로 보여 주면 틀린 안내가 된다. 다시 만들게 둔다.
        when(persistence.load(1L, 10L)).thenReturn(context(saved(false)));

        assertThat(service().find(1L, 10L).status()).isEqualTo(GuideStatus.NOT_GENERATED);
    }

    @Test
    void 생성은_쓸_수_있는_결과가_있으면_AI_를_부르지_않는다() {
        when(persistence.load(1L, 10L)).thenReturn(context(saved(true)));

        ApplicationGuideResponse response = service().generate(1L, 10L);

        assertThat(response.status()).isEqualTo(GuideStatus.READY);
        assertThat(response.cached()).isTrue();
        verify(persistence, never()).countGeneratedToday();
        verify(persistence, never()).save(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void 생성은_API_키가_없으면_AI_NOT_CONFIGURED_로_끝난다() {
        when(persistence.load(1L, 10L)).thenReturn(context(null));
        GeminiProperties noKey = new GeminiProperties(null, null);

        ApplicationGuideResponse response = service(noKey, defaultProperties()).generate(1L, 10L);

        assertThat(response.status()).isEqualTo(GuideStatus.UNAVAILABLE);
        assertThat(response.reason()).isEqualTo(GuideUnavailableReason.AI_NOT_CONFIGURED);
        assertThat(response.reasonMessage()).isNotBlank();
        // 실패해도 원문 링크는 항상 내려줘야 한다
        assertThat(response.noticeDetailUrl()).isEqualTo("https://apply.lh.or.kr/10");
    }

    @Test
    void 생성은_하루_상한에_걸리면_QUOTA_EXCEEDED_로_끝난다() {
        when(persistence.load(1L, 10L)).thenReturn(context(null));
        when(persistence.countGeneratedToday()).thenReturn(5L);
        ApplicationGuideProperties limited = new ApplicationGuideProperties("flash-lite", null, null, 0, 0, 0, 0, 5);

        ApplicationGuideResponse response = service(configuredGemini(), limited).generate(1L, 10L);

        assertThat(response.status()).isEqualTo(GuideStatus.UNAVAILABLE);
        assertThat(response.reason()).isEqualTo(GuideUnavailableReason.QUOTA_EXCEEDED);
    }

    @Test
    void 하루_상한을_비워_두면_건수를_세지_않는다() {
        when(persistence.load(1L, 10L)).thenReturn(context(null));

        service().generate(1L, 10L);

        verify(persistence, never()).countGeneratedToday();
    }

    // ---------- 공고문을 읽지 못하는 경로 ----------
    // 모두 예외가 아니라 UNAVAILABLE 상태로 끝나야 한다.
    // AI 가이드 실패가 관심 공고·지도·일정 화면을 깨뜨려서는 안 된다.

    @Test
    void 분석할_공고문이_없으면_NO_DOCUMENT_로_끝난다() {
        when(persistence.load(1L, 10L)).thenReturn(context(null));
        when(locator.locate(any())).thenReturn(List.of());

        ApplicationGuideResponse response = service().generate(1L, 10L);

        assertThat(response.status()).isEqualTo(GuideStatus.UNAVAILABLE);
        assertThat(response.reason()).isEqualTo(GuideUnavailableReason.NO_DOCUMENT);
        verify(extractor, never()).extract(any());
    }

    @Test
    void 스캔된_공고문이면_그_이유를_그대로_내려준다() {
        // 실제로 SH 매입임대 모집공고문 중에 폰트 없이 이미지 20장으로만 된 PDF 가 있었다
        when(persistence.load(1L, 10L)).thenReturn(context(null));
        when(locator.locate(any())).thenReturn(List.of(document()));
        when(extractor.extract(any())).thenThrow(new GuideDocumentException(
                GuideUnavailableReason.SCANNED_DOCUMENT, "글자가 너무 적습니다"));

        ApplicationGuideResponse response = service().generate(1L, 10L);

        assertThat(response.reason()).isEqualTo(GuideUnavailableReason.SCANNED_DOCUMENT);
        assertThat(response.reasonMessage()).isNotBlank();
        assertThat(response.noticeDetailUrl()).isEqualTo("https://apply.lh.or.kr/10");
    }

    @Test
    void 신청_준비_영역을_못_찾으면_전체를_보내지_않고_끝낸다() {
        // 여기서 전체 문서를 보내는 폴백을 두면 150쪽을 통째로 보내게 된다
        when(persistence.load(1L, 10L)).thenReturn(context(null));
        when(locator.locate(any())).thenReturn(List.of(document()));
        when(extractor.extract(any())).thenReturn(
                new ExtractedDocument("모집공고.pdf", DocumentFormat.PDF, List.of()));
        when(reducer.reduce(any())).thenReturn(java.util.Optional.empty());

        ApplicationGuideResponse response = service().generate(1L, 10L);

        assertThat(response.reason()).isEqualTo(GuideUnavailableReason.NO_RELEVANT_SECTION);
    }


    // ---------- Gemini 호출 경로 ----------

    /** 공고문 축약까지 성공한 상태를 만들어 둔다 */
    private void documentReady() {
        when(locator.locate(any())).thenReturn(List.of(document()));
        when(extractor.extract(any())).thenReturn(
                new ExtractedDocument("모집공고.pdf", DocumentFormat.PDF, List.of()));
        when(reducer.reduce(any())).thenReturn(java.util.Optional.of(reduced()));
    }

    private static ReducedDocument reduced() {
        return new ReducedDocument("[SOURCE_ID: S1]\nFILE: 모집공고.pdf\nPAGE: 4\n제출서류",
                Map.of("S1", GuideSourceRef.ofPage("모집공고.pdf", 4)),
                Set.of(SectionCategory.DOCUMENTS), 24_000, 1_200, List.of(4));
    }

    private static GeminiCompletion completion(String json) {
        return new GeminiCompletion(json, null, 1_000L);
    }

    private static final String VALID_JSON = """
            {"summary":"신청 준비 요약",
             "personalChecks":[{"title":"청년 연령","status":"MATCHED","detail":"설명","sourceIds":["S1"]}],
             "documents":[{"name":"주민등록등본","category":"COMMON","reason":"필수","requirements":"","sourceIds":["S1","S99"]}],
             "cautions":[{"kind":"MANUAL","text":"순위를 확인하세요","sourceIds":[]}]}
            """;

    @Test
    void 생성에_성공하면_결과를_저장하고_새로_만든_것으로_표시한다() {
        when(persistence.load(1L, 10L)).thenReturn(context(null));
        documentReady();
        when(geminiClient.generateJson(any(), any(), any())).thenReturn(completion(VALID_JSON));
        when(persistence.save(anyLong(), anyLong(), any(), any(), any()))
                .thenReturn(LocalDateTime.of(2026, 10, 26, 2, 42));

        ApplicationGuideResponse response = service().generate(1L, 10L);

        assertThat(response.status()).isEqualTo(GuideStatus.READY);
        assertThat(response.cached()).isFalse();
        assertThat(response.guide().summary()).isEqualTo("신청 준비 요약");
        assertThat(response.sources()).containsOnlyKeys("S1");
        verify(persistence).save(eq(1L), eq(10L), any(), eq(reduced().sources()), eq(PROFILE_AT));
    }

    @Test
    void AI_가_지어낸_출처는_저장_전에_걸러진다() {
        when(persistence.load(1L, 10L)).thenReturn(context(null));
        documentReady();
        when(geminiClient.generateJson(any(), any(), any())).thenReturn(completion(VALID_JSON));
        when(persistence.save(anyLong(), anyLong(), any(), any(), any())).thenReturn(LocalDateTime.now());

        ApplicationGuideResponse response = service().generate(1L, 10L);

        // 응답에 S99 가 들어 있었지만 우리가 준 적이 없으므로 지운다
        assertThat(response.guide().documents().getFirst().sourceIds()).containsExactly("S1");
    }

    @Test
    void 구조화_출력이_깨지면_한_번만_다시_시도한다() {
        when(persistence.load(1L, 10L)).thenReturn(context(null));
        documentReady();
        when(geminiClient.generateJson(any(), any(), any()))
                .thenReturn(completion("{깨진 JSON"))
                .thenReturn(completion(VALID_JSON));
        when(persistence.save(anyLong(), anyLong(), any(), any(), any())).thenReturn(LocalDateTime.now());

        ApplicationGuideResponse response = service().generate(1L, 10L);

        assertThat(response.status()).isEqualTo(GuideStatus.READY);
        verify(geminiClient, times(2)).generateJson(any(), any(), any());
    }

    @Test
    void 두_번째도_깨지면_포기한다() {
        // 무료 사용량을 쓰는 기능이라 계속 다시 걸지 않는다
        when(persistence.load(1L, 10L)).thenReturn(context(null));
        documentReady();
        when(geminiClient.generateJson(any(), any(), any())).thenReturn(completion("{깨진 JSON"));

        ApplicationGuideResponse response = service().generate(1L, 10L);

        assertThat(response.reason()).isEqualTo(GuideUnavailableReason.AI_FAILED);
        verify(geminiClient, times(2)).generateJson(any(), any(), any());
        verify(persistence, never()).save(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void 사용량을_초과하면_다시_시도하지_않고_끝낸다() {
        when(persistence.load(1L, 10L)).thenReturn(context(null));
        documentReady();
        when(geminiClient.generateJson(any(), any(), any())).thenThrow(
                new GeminiException(GuideUnavailableReason.QUOTA_EXCEEDED, "사용량 초과"));

        ApplicationGuideResponse response = service().generate(1L, 10L);

        assertThat(response.reason()).isEqualTo(GuideUnavailableReason.QUOTA_EXCEEDED);
        verify(geminiClient, times(1)).generateJson(any(), any(), any());
        verify(persistence, never()).save(anyLong(), anyLong(), any(), any(), any());
    }

    private static LocatedDocument document() {
        return new LocatedDocument("모집공고.pdf", "https://example.test/a.pdf", DocumentFormat.PDF, 110);
    }
}
