package com.zipick.infrastructure.service;

import com.zipick.external.kakao.client.KakaoCategorySearchClient;
import com.zipick.external.kakao.dto.KakaoCategorySearchResponse;
import com.zipick.infrastructure.config.InfraPolicy;
import com.zipick.infrastructure.config.InfraProperties.Category;
import com.zipick.infrastructure.service.InfraCollectionResult.CollectedCategory;
import com.zipick.infrastructure.service.InfraCollectionResult.NearestPlace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 주택 한 채의 주변 인프라를 카테고리별로 조회한다. 외부 호출만 하고 DB 는 건드리지 않는다.
 * 트랜잭션 밖에서 실행되도록 호출자(스케줄러)가 보장한다.
 *
 * 한 카테고리라도 실패하면 전체를 실패로 돌려준다.
 * 부분 저장을 허용하면 "Place 행이 없다 = 0건"이라는 규칙이 깨지기 때문이다.
 */
@Component
public class HousingInfraCollector {

    private static final Logger log = LoggerFactory.getLogger(HousingInfraCollector.class);

    private final KakaoCategorySearchClient client;
    private final InfraPolicy policy;

    public HousingInfraCollector(KakaoCategorySearchClient client, InfraPolicy policy) {
        this.client = client;
        this.policy = policy;
    }

    public InfraCollectionResult collect(Long housingId, BigDecimal latitude, BigDecimal longitude) {
        List<CollectedCategory> collected = new ArrayList<>();
        int apiCalls = 0;

        for (Category category : policy.categories()) {
            KakaoCategorySearchResponse response;
            try {
                response = client.search(category.kakaoCode(), latitude, longitude, category.radiusM());
                apiCalls++;
            } catch (RuntimeException exception) {
                log.warn("카카오 카테고리 검색 호출 실패. housingId={}, category={}, reason={}",
                        housingId, category.code(), exception.getMessage());
                return InfraCollectionResult.apiError(apiCalls);
            }

            CollectedCategory parsed = parse(category, response);
            if (parsed == null) {
                log.warn("카카오 카테고리 검색 응답이 올바르지 않습니다. housingId={}, category={}",
                        housingId, category.code());
                return InfraCollectionResult.dataError(apiCalls);
            }
            collected.add(parsed);
        }

        return InfraCollectionResult.success(collected, apiCalls);
    }

    /** 응답을 해석한다. 정상 0건과 비정상 응답을 섞지 않는 것이 이 메서드의 핵심이다. */
    private CollectedCategory parse(Category category, KakaoCategorySearchResponse response) {
        KakaoCategorySearchResponse.Meta meta = response.meta();
        if (meta == null || meta.totalCount() == null || meta.totalCount() < 0) {
            return null;
        }

        int totalCount = meta.totalCount();
        if (totalCount == 0) {
            // 정상적으로 검색했고 반경 안에 시설이 없는 경우. Place 행을 만들지 않는다.
            return new CollectedCategory(category, 0, null);
        }

        if (response.documents().isEmpty()) {
            // 전체 개수는 0이 아닌데 문서가 없다면 비정상 응답이다. 0건으로 처리하지 않는다.
            return null;
        }

        KakaoCategorySearchResponse.Document document = response.documents().getFirst();
        Integer distance = toDistance(document.distance());
        if (!StringUtils.hasText(document.placeName()) || distance == null) {
            return null;
        }

        NearestPlace nearest = new NearestPlace(
                trimToNull(document.id()),
                document.placeName().trim(),
                distance,
                trimToNull(document.roadAddressName()),
                trimToNull(document.addressName()),
                toCoordinate(document.y()),
                toCoordinate(document.x()),
                trimToNull(document.placeUrl())
        );
        return new CollectedCategory(category, totalCount, nearest);
    }

    private static Integer toDistance(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            int distance = Integer.parseInt(value.trim());
            return distance < 0 ? null : distance;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** 좌표는 화면에 쓰지 않는 부가정보라 파싱에 실패해도 수집 자체는 성공으로 둔다. */
    private static BigDecimal toCoordinate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
