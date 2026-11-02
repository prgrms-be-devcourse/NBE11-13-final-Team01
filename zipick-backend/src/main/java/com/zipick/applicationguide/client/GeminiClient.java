package com.zipick.applicationguide.client;

import com.zipick.applicationguide.config.ApplicationGuideProperties;
import com.zipick.applicationguide.config.GeminiProperties;
import com.zipick.applicationguide.dto.GuideUnavailableReason;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * AI 신청 준비 가이드 전용 Gemini 클라이언트.
 *
 * <h3>전용으로 둔 이유</h3>
 * 팀원이 만들고 있는 공고 수집 보조 AI 에도 Gemini 전송 코드가 있지만, 그쪽은 PDF 원본을
 * 통째로 보내는 구조와 전용 설정에 묶여 있고 아직 dev 에 들어오지 않았다.
 * 그 코드를 기다리거나 먼저 리팩토링하는 것을 이 기능의 선행조건으로 삼지 않기로 했다.
 * 대신 <b>연결 방식만</b> 맞춰 두었다 — REST generateContent, x-goog-api-key,
 * GEMINI_API_KEY(공통 인증), Structured JSON Output.
 * 반대로 모델·타임아웃·호출 상한처럼 기능마다 달라야 하는 정책은 공유하지 않는다
 * (ApplicationGuideProperties). 나중에 두 기능이 모두 안정되면 저수준 전송 부분만 합치면 된다.
 *
 * <h3>재시도를 하지 않는 이유</h3>
 * 무료 사용량을 쓰는 기능이라 호출 수가 곧 비용이다. 429(사용량 초과)·타임아웃·5xx 모두
 * 다시 걸어도 같은 결과일 가능성이 높고, 자동 재시도는 사용량만 예상 밖으로 늘린다.
 * 사용자가 화면에서 다시 누르는 것이 가장 안전한 재시도다.
 */
@Slf4j
@Component
public class GeminiClient {

    /** 공고문에 있는 사실만 정리하는 작업이라 낮게 둔다. 창의성이 필요한 일이 아니다 */
    private static final double TEMPERATURE = 0.2;

    private final RestClient restClient;
    /** 공통: 인증 키 */
    private final GeminiProperties gemini;
    /** 이 기능 전용: 모델 */
    private final ApplicationGuideProperties guide;

    public GeminiClient(RestClient applicationGuideGeminiRestClient,
                        GeminiProperties gemini,
                        ApplicationGuideProperties guide) {
        this.restClient = applicationGuideGeminiRestClient;
        this.gemini = gemini;
        this.guide = guide;
    }

    /**
     * 구조화된 JSON 을 한 번 받아 온다.
     *
     * @param responseJsonSchema 모델이 따라야 할 JSON 스키마
     * @throws GeminiException 호출 실패. reason 에 사용자에게 보여 줄 이유가 담긴다
     */
    public GeminiCompletion generateJson(String systemInstruction, String userText,
                                         Map<String, Object> responseJsonSchema) {
        if (!gemini.isConfigured()) {
            throw new GeminiException(GuideUnavailableReason.AI_NOT_CONFIGURED, "GEMINI_API_KEY 가 설정되지 않았습니다.");
        }
        GeminiRequest request = GeminiRequest.of(systemInstruction, userText, responseJsonSchema, TEMPERATURE);
        String path = "/v1beta/models/" + guide.model() + ":generateContent";

        long startedAt = System.currentTimeMillis();
        GeminiResponse response = send(path, request);
        long elapsedMs = System.currentTimeMillis() - startedAt;

        if (response.blockReason() != null) {
            throw new GeminiException(GuideUnavailableReason.AI_FAILED,
                    "Gemini 가 요청을 거부했습니다: " + response.blockReason());
        }
        String json = response.text();
        if (json == null) {
            // 보통 MAX_TOKENS 로 잘렸거나 안전 필터에 걸린 경우다
            throw new GeminiException(GuideUnavailableReason.AI_FAILED,
                    "Gemini 응답이 비어 있습니다. finishReason=" + response.finishReason());
        }
        GeminiCompletion completion = new GeminiCompletion(json, response.usageMetadata(), elapsedMs);
        log.info("Gemini 호출 완료: model={}, finishReason={}, {}",
                guide.model(), response.finishReason(), completion.usageSummary());
        return completion;
    }

    private GeminiResponse send(String path, GeminiRequest request) {
        try {
            return restClient.post()
                    .uri(path)
                    // 키는 헤더로만 보낸다. 쿼리 스트링에 넣으면 접근 로그에 그대로 남는다
                    .header("x-goog-api-key", gemini.key())
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw toException(res.getStatusCode(), readBody(res));
                    })
                    .body(GeminiResponse.class);
        } catch (GeminiException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            // 연결 실패·읽기 타임아웃. 재시도하지 않는다
            throw new GeminiException(GuideUnavailableReason.AI_FAILED,
                    "Gemini 응답을 받지 못했습니다: " + exception.getMessage(), exception);
        } catch (RestClientException exception) {
            throw new GeminiException(GuideUnavailableReason.AI_FAILED,
                    "Gemini 호출에 실패했습니다: " + exception.getMessage(), exception);
        }
    }

    /** 사용량 초과만 따로 구분한다. 화면 안내 문구가 달라야 하기 때문이다 */
    private GeminiException toException(HttpStatusCode status, String body) {
        if (status.value() == 429) {
            log.warn("Gemini 무료 사용량을 초과했습니다. status=429");
            return new GeminiException(GuideUnavailableReason.QUOTA_EXCEEDED, "Gemini 사용량을 초과했습니다.");
        }
        // 본문에는 키가 들어가지 않는다 (키는 헤더로만 보낸다)
        log.warn("Gemini 호출 실패: status={}, body={}", status.value(), body);
        return new GeminiException(GuideUnavailableReason.AI_FAILED,
                "Gemini 호출 실패: status=" + status.value());
    }

    private static String readBody(org.springframework.http.client.ClientHttpResponse response) {
        try (var stream = response.getBody()) {
            String body = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return body.length() > 500 ? body.substring(0, 500) + "..." : body;
        } catch (Exception ignored) {
            return "(본문 없음)";
        }
    }
}
