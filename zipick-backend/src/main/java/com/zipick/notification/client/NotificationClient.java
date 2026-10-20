package com.zipick.notification.client;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.global.response.ApiResponse;
import com.zipick.notification.config.NotificationProperties;
import com.zipick.notification.dto.NotificationEventResponse;
import com.zipick.notification.dto.NotificationSettingRequest;
import com.zipick.notification.dto.NotificationSettingResponse;
import com.zipick.notification.queue.NotificationPriority;
import com.zipick.notification.queue.NotificationPublisher;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 알림센터 클라이언트. 메인 서버에서 알림과 관련해 바깥으로 나가는 호출은 전부 여기를 지난다.
 *
 * 두 가지 길이 있다.
 *  - 응답이 필요 없는 "알림 요청"(마감·새 공고·인증 메일) → {@link #request}: 큐(RabbitMQ)에 던진다. 알림센터가 꺼져 있어도 요청이 남는다.
 *    큐가 꺼져 있거나(queue-enabled=false) 브로커가 답이 없으면 HTTP 로 보낸다.
 *  - 응답이 필요한 조회·설정·테스트 발송 → HTTP. 알림센터가 죽어 있으면 BusinessException(NOTIFICATION_UNAVAILABLE).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationClient {

    private final RestClient notificationRestClient;
    private final NotificationProperties props;
    /** 큐가 꺼져 있으면 빈 Optional (빈이 없음) */
    private final Optional<NotificationPublisher> publisher;
    /** 마지막으로 성공한 구독자 목록. 알림센터가 잠깐 꺼져 있어도 스케줄러가 큐에 요청을 던질 수 있게 */
    private final AtomicReference<List<NotificationSettingResponse>> lastSubscribers = new AtomicReference<>(List.of());

    public boolean configured() { return props.configured(); }

    /**
     * 알림 요청. 큐가 켜져 있으면 던지고 끝(true). 큐에 못 넣으면 HTTP 로 보낸다.
     * @return 큐 또는 HTTP 로 전달됐으면 true. 둘 다 실패하면 BusinessException(NOTIFICATION_UNAVAILABLE)
     */
    public boolean request(NotificationRequest request, NotificationPriority priority) {
        if (publisher.isPresent() && publisher.get().publish(request, priority)) return true;
        enqueue(request);
        return true;
    }

    /** HTTP 로 알림 요청을 적는다. 같은 dedupeKey 면 알림센터가 기존 건을 돌려준다(duplicate=true) */
    public NotificationEventResponse enqueue(NotificationRequest request) {
        return call(() -> notificationRestClient.post().uri("/internal/notifications").body(request)
                .retrieve().body(new ParameterizedTypeReference<ApiResponse<NotificationEventResponse>>() {}));
    }

    public NotificationSettingResponse getSubscription(Long userId) {
        return call(() -> notificationRestClient.get().uri("/internal/subscriptions/{id}", userId)
                .retrieve().body(new ParameterizedTypeReference<ApiResponse<NotificationSettingResponse>>() {}));
    }

    public NotificationSettingResponse putSubscription(Long userId, NotificationSettingRequest request) {
        return call(() -> notificationRestClient.put().uri("/internal/subscriptions/{id}", userId).body(request)
                .retrieve().body(new ParameterizedTypeReference<ApiResponse<NotificationSettingResponse>>() {}));
    }

    /**
     * 정보성 알림이 켜진 사용자 전체 (스케줄러가 누구에게 적을지 정할 때).
     * 알림센터에 닿지 않으면 마지막으로 받아 둔 목록을 쓴다 — 큐가 켜져 있으면 요청은 던져 둘 수 있으니까.
     * 한 번도 받은 적이 없으면(기동 직후) 그대로 실패한다.
     */
    public List<NotificationSettingResponse> listEnabledSubscriptions() {
        try {
            List<NotificationSettingResponse> fresh = call(() -> notificationRestClient.get().uri("/internal/subscriptions")
                    .retrieve().body(new ParameterizedTypeReference<ApiResponse<List<NotificationSettingResponse>>>() {}));
            lastSubscribers.set(fresh);
            return fresh;
        } catch (BusinessException e) {
            List<NotificationSettingResponse> cached = lastSubscribers.get();
            if (publisher.isEmpty() || cached.isEmpty()) throw e;
            log.warn("알림센터에 닿지 않아 마지막 구독자 목록({}명)으로 진행합니다. 요청은 큐에 남습니다.", cached.size());
            return cached;
        }
    }

    public NotificationEventResponse sendTest(Long userId) {
        return call(() -> notificationRestClient.post().uri("/internal/subscriptions/{id}/test", userId)
                .retrieve().body(new ParameterizedTypeReference<ApiResponse<NotificationEventResponse>>() {}));
    }

    public List<NotificationEventResponse> history(Long userId) {
        return call(() -> notificationRestClient.get().uri(b -> b.path("/internal/notifications").queryParam("userId", userId).build())
                .retrieve().body(new ParameterizedTypeReference<ApiResponse<List<NotificationEventResponse>>>() {}));
    }

    private <T> T call(Supplier<ApiResponse<T>> request) {
        if (!props.configured()) throw new BusinessException(ErrorCode.NOTIFICATION_UNAVAILABLE, "알림센터 키(NOTIFICATION_INTERNAL_KEY)가 설정되지 않았습니다.");
        try {
            ApiResponse<T> res = request.get();
            if (res == null) throw new BusinessException(ErrorCode.NOTIFICATION_UNAVAILABLE);
            if (!res.success()) {
                String msg = res.error() == null ? "알림센터 오류" : res.error().message();
                throw new BusinessException(ErrorCode.INVALID_INPUT, msg);
            }
            return res.data();
        } catch (RestClientResponseException e) {
            // 알림센터가 4xx 로 거부한 경우 메시지를 그대로 전달 (예: 잘못된 웹훅 주소)
            String msg = extractMessage(e);
            HttpStatusCode st = e.getStatusCode();
            if (st.is4xxClientError() && st.value() != 401) throw new BusinessException(ErrorCode.INVALID_INPUT, msg);
            log.warn("알림센터 응답 오류 {}: {}", st, msg);
            throw new BusinessException(ErrorCode.NOTIFICATION_UNAVAILABLE);
        } catch (ResourceAccessException e) {
            log.warn("알림센터 연결 실패: {}", e.getMessage());
            throw new BusinessException(ErrorCode.NOTIFICATION_UNAVAILABLE);
        }
    }

    private static String extractMessage(RestClientResponseException e) {
        try {
            ApiResponse<?> body = e.getResponseBodyAs(ApiResponse.class);
            if (body != null && body.error() != null && body.error().message() != null) return body.error().message();
        } catch (Exception ignored) { }
        return e.getStatusText();
    }
}
