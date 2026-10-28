package com.zipick.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * 주변 인프라 수집 설정. (application.yml 의 zipick.infra)
 * 카테고리와 반경을 코드가 아닌 설정으로 두어 정책 변경 비용을 낮춘다.
 */
@ConfigurationProperties(prefix = "zipick.infra")
public record InfraProperties(
        Duration cacheTtl,
        Collection collection,
        List<Category> categories
) {

    private static final Duration DEFAULT_TTL = Duration.ofDays(30);

    public InfraProperties {
        cacheTtl = cacheTtl == null ? DEFAULT_TTL : cacheTtl;
        collection = collection == null ? Collection.defaults() : collection;
        categories = categories == null ? List.of() : List.copyOf(categories);
    }

    /**
     * 수집 실행 설정.
     *
     * <p>실행 시각 관련 값(startup-delay / catch-up-interval / daily-cron / zone)은
     * {@code @Scheduled} 애너테이션이 {@code ${...}} 로 직접 읽는다. 애너테이션 값은 빈이 만들어지기 전에
     * 평가되므로 이 레코드를 거칠 수 없다. 그래서 여기에는 <b>코드가 실제로 쓰는 값만</b> 둔다.
     *
     * @param catchUpWindow 기동 수집 이후 보정 실행을 유지하는 기간. 공고 수집이 늦게 끝나 뒤늦게
     *                      좌표가 채워진 주택을 다음 날 cron 까지 기다리지 않고 줍기 위한 창이다.
     *                      이 시간이 지나면 보정 실행은 은퇴하고 하루 1회 cron 만 남는다.
     */
    public record Collection(
            boolean enabled,
            int batchSize,
            int apiFailureThreshold,
            Duration catchUpWindow
    ) {
        // application.yml 의 catch-up-window 와 같은 값으로 둔다. 설정이 빠졌을 때 조용히
        // 더 짧은 창으로 되돌아가면, 공고 수집이 늦게 끝난 주택이 다음 날 cron 까지 남는다.
        // 3시간은 AI ON + 빈 DB 최초 전건 공고 수집 실측(2시간 21분 25초)을 수용하는 임시 안전값이다.
        private static final Duration DEFAULT_CATCH_UP_WINDOW = Duration.ofHours(3);

        public Collection {
            batchSize = batchSize <= 0 ? 100 : batchSize;
            apiFailureThreshold = apiFailureThreshold <= 0 ? 3 : apiFailureThreshold;
            catchUpWindow = catchUpWindow == null ? DEFAULT_CATCH_UP_WINDOW : catchUpWindow;
        }

        static Collection defaults() {
            return new Collection(true, 100, 3, DEFAULT_CATCH_UP_WINDOW);
        }
    }

    /**
     * @param code     서비스 코드 (SUBWAY). 응답·DB 에 쓴다.
     * @param kakaoCode 카카오 카테고리 그룹 코드 (SW8). 외부 호출과 nearest JSON 의 key 로 쓴다.
     * @param radiusM  카테고리별 검색 반경(m)
     */
    public record Category(
            String code,
            String kakaoCode,
            String label,
            int radiusM
    ) {
    }
}
