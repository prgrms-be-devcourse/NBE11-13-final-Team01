package com.zipick.applicationguide.service;

import com.zipick.applicationguide.client.GeminiClient;
import com.zipick.applicationguide.client.GeminiCompletion;
import com.zipick.applicationguide.client.GeminiException;
import com.zipick.applicationguide.config.ApplicationGuideProperties;
import com.zipick.applicationguide.config.GeminiProperties;
import com.zipick.applicationguide.document.ApplicationSectionReducer;
import com.zipick.applicationguide.document.ExtractedDocument;
import com.zipick.applicationguide.document.GuideDocumentException;
import com.zipick.applicationguide.document.LocatedDocument;
import com.zipick.applicationguide.document.NoticeDocumentLocator;
import com.zipick.applicationguide.document.NoticeDocumentTextExtractor;
import com.zipick.applicationguide.document.ReducedDocument;
import com.zipick.applicationguide.dto.ApplicationGuideResponse;
import com.zipick.applicationguide.dto.GuideRequestContext;
import com.zipick.applicationguide.dto.GuideResult;
import com.zipick.applicationguide.dto.GuideSourceRef;
import com.zipick.applicationguide.dto.GuideUnavailableReason;
import com.zipick.global.util.JsonUtil;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

/**
 * AI 신청 준비 가이드 흐름 전체를 엮는 곳.
 *
 * <pre>
 * 관심 공고 확인 → 저장된 결과 확인 → (없으면) 공고문 확보 → 텍스트 추출·축약 → Gemini → 저장
 * </pre>
 *
 * 일부러 트랜잭션을 걸지 않았다. 문서 다운로드와 Gemini 호출이 수십 초 걸릴 수 있어
 * 그동안 DB 커넥션을 붙잡고 있으면 안 되기 때문이다.
 * DB 접근은 ApplicationGuidePersistenceService 가 짧은 트랜잭션으로 따로 처리한다.
 *
 * 생성 실패를 예외로 던지지 않고 UNAVAILABLE 상태로 돌려주는 것도 의도적이다.
 * AI 가 실패해도 사용자는 공고 원문을 계속 볼 수 있어야 하고,
 * 관심 공고·지도·일정 같은 기존 기능이 영향을 받아서는 안 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicationGuideService {

    private final ApplicationGuidePersistenceService persistence;
    private final NoticeDocumentLocator locator;
    private final NoticeDocumentTextExtractor extractor;
    private final ApplicationSectionReducer reducer;
    private final GeminiClient geminiClient;
    private final ApplicationGuideProperties properties;
    private final GeminiProperties geminiProperties;

    /** 저장된 결과만 돌려준다. 어떤 경우에도 Gemini 를 부르지 않는다 */
    public ApplicationGuideResponse find(Long userId, Long noticeId) {
        GuideRequestContext context = persistence.load(userId, noticeId);
        return reusableResponse(context, true)
                .orElseGet(() -> ApplicationGuideResponse.notGenerated(context.header()));
    }

    /** 사용자가 '가이드 만들기' 를 눌렀을 때. 저장된 결과가 아직 쓸 수 있으면 그것을 그대로 돌려준다 */
    public ApplicationGuideResponse generate(Long userId, Long noticeId) {
        GuideRequestContext context = persistence.load(userId, noticeId);

        Optional<ApplicationGuideResponse> cached = reusableResponse(context, true);
        if (cached.isPresent()) {
            return cached.get();
        }
        if (!geminiProperties.isConfigured()) {
            log.warn("GEMINI_API_KEY 가 없어 가이드를 만들 수 없습니다. noticeId={}", noticeId);
            return ApplicationGuideResponse.unavailable(context.header(), GuideUnavailableReason.AI_NOT_CONFIGURED);
        }
        if (properties.hasDailyBudget() && persistence.countGeneratedToday() >= properties.dailyCallBudget()) {
            log.warn("가이드 일일 생성 상한에 걸렸습니다. limit={}", properties.dailyCallBudget());
            return ApplicationGuideResponse.unavailable(context.header(), GuideUnavailableReason.QUOTA_EXCEEDED);
        }

        Optional<ReducedDocument> reduced;
        try {
            reduced = readNoticeDocument(context);
        } catch (GuideDocumentException exception) {
            log.warn("공고문을 읽지 못했습니다. noticeId={}, reason={}, message={}",
                    noticeId, exception.getReason(), exception.getMessage());
            return ApplicationGuideResponse.unavailable(context.header(), exception.getReason());
        }
        if (reduced.isEmpty()) {
            return ApplicationGuideResponse.unavailable(context.header(), GuideUnavailableReason.NO_RELEVANT_SECTION);
        }

        ReducedDocument document = reduced.get();
        GuideResult result;
        try {
            result = askGemini(context, document);
        } catch (GeminiException exception) {
            log.warn("AI 가이드 생성에 실패했습니다. noticeId={}, reason={}, message={}",
                    noticeId, exception.getReason(), exception.getMessage());
            return ApplicationGuideResponse.unavailable(context.header(), exception.getReason());
        }

        Map<String, GuideSourceRef> sources = document.sources();
        LocalDateTime generatedAt = persistence.save(userId, noticeId, result, sources, context.profileUpdatedAt());
        log.info("AI 가이드를 만들었습니다. noticeId={}, 서류={}건, 확인사항={}건, 유의사항={}건",
                noticeId, result.documents().size(), result.personalChecks().size(), result.cautions().size());
        return ApplicationGuideResponse.ready(context.header(), result, sources,
                properties.model(), generatedAt, false);
    }

    /**
     * Gemini 를 부르고 결과를 쓸 수 있는 형태로 다듬는다.
     *
     * 구조화 출력이 깨졌을 때만 한 번 더 시도한다. 모델이 간헐적으로 JSON 을 잘못 닫는 경우가 있어
     * 그때는 다시 걸면 대개 성공한다. 반대로 429(사용량 초과)·타임아웃·5xx 는 다시 걸어도
     * 같은 결과일 가능성이 높고 무료 사용량만 축내므로 재시도하지 않는다.
     */
    private GuideResult askGemini(GuideRequestContext context, ReducedDocument document) {
        String system = ApplicationGuidePrompt.SYSTEM_INSTRUCTION;
        String user = ApplicationGuidePrompt.userMessage(context, document);
        List<String> sourceIds = List.copyOf(document.sources().keySet());
        Map<String, Object> schema = ApplicationGuidePrompt.responseSchema(sourceIds);

        log.info("Gemini 요청: noticeId={}, 전송 {}자(원문 {}자에서 {}% 덜어냄), 출처={}건",
                context.noticeId(), document.sentChars(), document.originalChars(),
                document.reductionPercent(), sourceIds.size());

        GeminiCompletion completion = geminiClient.generateJson(system, user, schema);
        GuideResult parsed = parse(completion);
        if (parsed == null) {
            log.warn("Gemini 응답을 읽지 못해 한 번만 다시 시도합니다. noticeId={}", context.noticeId());
            parsed = parse(geminiClient.generateJson(system, user, schema));
        }
        if (parsed == null) {
            throw new GeminiException(GuideUnavailableReason.AI_FAILED, "Gemini 응답을 JSON 으로 읽지 못했습니다.");
        }
        return GuideResultSanitizer.sanitize(parsed, document.sources());
    }

    /** 읽지 못하면 null. 여기서 예외를 던지지 않는 이유는 한 번만 다시 시도해 보기 위해서다 */
    private GuideResult parse(GeminiCompletion completion) {
        GuideResult result = JsonUtil.read(completion.json(), new TypeReference<GuideResult>() {}, null);
        return result == null || result.summary() == null ? null : result;
    }

    /**
     * 공고문을 찾아 신청 준비와 관련된 부분만 남긴다.
     *
     * 여러 건이 걸려도 점수가 가장 높은 한 건만 분석한다. 한국 모집공고는 보통 공고문 한 파일에
     * 자격·서류·절차·유의사항이 모두 들어 있어서, 여러 파일을 합치면 토큰만 늘고 내용이 겹친다.
     * (maxDocuments 는 후보를 몇 건까지 추릴지를 정한다)
     *
     * @return 신청 준비 관련 영역을 찾지 못했으면 빈 값
     */
    private Optional<ReducedDocument> readNoticeDocument(GuideRequestContext context) {
        List<LocatedDocument> documents = locator.locate(context);
        if (documents.isEmpty()) {
            log.info("분석할 공고문을 찾지 못했습니다. noticeId={}, source={}", context.noticeId(), context.source());
            throw new GuideDocumentException(GuideUnavailableReason.NO_DOCUMENT,
                    "공고문 첨부를 찾지 못했습니다: noticeId=" + context.noticeId());
        }
        LocatedDocument best = documents.getFirst();
        ExtractedDocument extracted = extractor.extract(best);
        return reducer.reduce(extracted);
    }

    /**
     * 저장된 결과를 그대로 쓸 수 있으면 응답으로 만든다.
     *
     * 다시 만들어야 하는 결과(프로필·프롬프트·모델이 바뀐 경우)는 일부러 보여 주지 않는다.
     * 예를 들어 사용자가 '대학생' 을 해제했는데 "현재 대학생으로 등록되어 있어..." 로 시작하는
     * 예전 가이드를 현재 결과인 것처럼 보여 주면 틀린 안내가 된다.
     */
    private Optional<ApplicationGuideResponse> reusableResponse(GuideRequestContext context, boolean cached) {
        GuideRequestContext.SavedGuide saved = context.saved();
        if (saved == null || !saved.reusable() || saved.result() == null) {
            return Optional.empty();
        }
        return Optional.of(ApplicationGuideResponse.ready(
                context.header(), saved.result(), saved.sources(), saved.model(), saved.generatedAt(), cached));
    }
}
