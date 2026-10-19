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

    public record Collection(
            boolean enabled,
            int batchSize,
            int apiFailureThreshold
    ) {
        public Collection {
            batchSize = batchSize <= 0 ? 100 : batchSize;
            apiFailureThreshold = apiFailureThreshold <= 0 ? 3 : apiFailureThreshold;
        }

        static Collection defaults() {
            return new Collection(true, 100, 3);
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
