package com.zipick.notification.client;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.global.response.ApiResponse;
import com.zipick.notification.config.NotificationProperties;
import com.zipick.notification.dto.NotificationEventResponse;
import com.zipick.notification.queue.NotificationPriority;
import com.zipick.notification.queue.NotificationPublisher;
import java.util.List;
import java.util.Map;
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
 * 알림센터 클라이언트 (스펙 v1, docs/notification-api.md). 메인 서버에서 알림센터로 나가는 호출은 전부 여기를 지난다.
 *
 * 두 가지 길이 있다.
 *  - 응답이 필요 없는 "알림 요청"(마감·새 공고·인증 메일) → {@link #request}: 큐(RabbitMQ)에 던진다. 알림센터가 꺼져 있어도 요청이 남는다.
 *    큐가 꺼져 있거나(queue-enabled=false) 브로커가 답이 없으면 HTTP 로 보낸다.
 *  - 응답이 필요한 테스트 발송·내역·묶음 → HTTP. 알림센터가 죽어 있으면 BusinessException(NOTIFICATION_UNAVAILABLE).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationClient {

    private final RestClient notificationRestClient;
    private final NotificationProperties props;
    /** 큐가 꺼져 있으면 빈 Optional (빈이 없음) */
    private final Optional<NotificationPublisher> publisher;
    /** 마지막으로 확인한 채널 가용 여부. 설정 화면이 열릴 때마다 알림센터를 부르지 않게 */
    private final AtomicReference<Map<String, Boolean>> channels = new AtomicReference<>();

    public boolean configured() { return props.configured(); }

    /**
     * 알림 요청. 큐가 켜져 있으면 던지고 끝. 큐에 못 넣으면 HTTP 로 보낸다.
     * 둘 다 실패하면 BusinessException(NOTIFICATION_UNAVAILABLE)
     */
    public void request(NotificationRequest request, NotificationPriority priority) {
        if (publisher.isPresent() && publisher.get().publish(request, priority)) return;
        enqueue(request);
    }

    /** HTTP 로 알림 요청을 적는다. 같은 dedupeKey 면 알림센터가 기존 건을 돌려준다(duplicate=true). delivery=now 면 결과까지 */
    public NotificationEventResponse enqueue(NotificationRequest request) {
        return call(() -> notificationRestClient.post().uri("/internal/v1/notifications").body(request)
                .retrieve().body(new ParameterizedTypeReference<ApiResponse<NotificationEventResponse>>() {}));
    }

    /** 사용자의 알림 내역 (tag user:{id}, 최근 50건) */
    public List<NotificationEventResponse> history(Long userId) {
        return call(() -> notificationRestClient.get()
                .uri(b -> b.path("/internal/v1/notifications").queryParam("tag", NotificationRequest.TAG_USER + ":" + userId).build())
                .retrieve().body(new ParameterizedTypeReference<ApiResponse<List<NotificationEventResponse>>>() {}));
    }

    /** 알림센터에 켜진 채널 (email/slack → true/false). 닿지 않으면 마지막 값, 그것도 없으면 null */
    public Map<String, Boolean> availableChannels() {
        try {
            Map<String, Boolean> fresh = call(() -> notificationRestClient.get().uri("/internal/v1/channels")
                    .retrieve().body(new ParameterizedTypeReference<ApiResponse<Map<String, Boolean>>>() {}));
            channels.set(fresh);
        } catch (BusinessException e) {
            log.debug("채널 상태를 받지 못함: {}", e.getMessage());
        }
        return channels.get();
    }

    /** 알림센터에 이메일 채널이 켜져 있는지. 닿지 않으면 마지막 값, 그것도 없으면 false */
    public boolean emailAvailable() {
        Map<String, Boolean> known = availableChannels();
        return known != null && Boolean.TRUE.equals(known.get("email"));
    }

    /**
     * 원하는 채널 중 알림센터가 지금 보낼 수 있는 것만. 알림센터는 꺼진 채널을 고른 요청을 통째로 거부하므로
     * (예: 이메일 provider=none 인데 email 을 고르면 400) 요청하는 쪽이 미리 뺀다.
     * 채널 상태를 한 번도 받지 못했으면 그대로 돌려준다 (큐에 남았다가 알림센터가 검사한다).
     */
    public List<String> usableChannels(List<String> wanted) {
        Map<String, Boolean> known = availableChannels();
        if (known == null) return wanted;
        return wanted.stream().filter(c -> Boolean.TRUE.equals(known.get(c))).toList();
    }

    public BatchResponse createBatch(BatchRequest request) {
        return call(() -> notificationRestClient.post().uri("/internal/v1/batches").body(request)
                .retrieve().body(new ParameterizedTypeReference<ApiResponse<BatchResponse>>() {}));
    }

    public BatchResponse getBatch(long id) {
        return call(() -> notificationRestClient.get().uri("/internal/v1/batches/{id}", id)
                .retrieve().body(new ParameterizedTypeReference<ApiResponse<BatchResponse>>() {}));
    }

    /** 아직 안 나간 메시지만 취소된다. 이미 나간 것은 그대로 */
    public BatchResponse cancelBatch(long id) {
        return call(() -> notificationRestClient.delete().uri("/internal/v1/batches/{id}", id)
                .retrieve().body(new ParameterizedTypeReference<ApiResponse<BatchResponse>>() {}));
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
            // 알림센터가 4xx 로 거부한 경우 메시지를 그대로 전달 (예: 템플릿에 없는 채널, 주소 없음)
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
