package com.zipick.infrastructure.service;

import com.zipick.infrastructure.config.InfraProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * 주택 한 채의 인프라 수집 결과.
 *
 * 실패를 두 가지로 나눈다.
 *   API_ERROR  : 통신 실패·타임아웃·쿼터 초과처럼 카카오 쪽 문제. 다른 주택도 실패할 가능성이 높다.
 *   DATA_ERROR : 응답은 받았지만 내용이 비정상인 경우. 이 주택만의 문제라 다음 주택은 계속 진행한다.
 * 이 구분 덕분에 특정 주택의 데이터 문제로 배치 전체가 멈추지 않는다.
 */
public record InfraCollectionResult(Status status, List<CollectedCategory> categories, int apiCalls) {

    public enum Status {
        SUCCESS,
        API_ERROR,
        DATA_ERROR
    }

    public InfraCollectionResult {
        categories = categories == null ? List.of() : List.copyOf(categories);
    }

    public static InfraCollectionResult success(List<CollectedCategory> categories, int apiCalls) {
        return new InfraCollectionResult(Status.SUCCESS, categories, apiCalls);
    }

    public static InfraCollectionResult apiError(int apiCalls) {
        return new InfraCollectionResult(Status.API_ERROR, List.of(), apiCalls);
    }

    public static InfraCollectionResult dataError(int apiCalls) {
        return new InfraCollectionResult(Status.DATA_ERROR, List.of(), apiCalls);
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    /** 카테고리 1개의 수집 결과. 반경 내 0건이면 nearest 가 null 이고 searchCount 가 0이다. */
    public record CollectedCategory(
            InfraProperties.Category category,
            int searchCount,
            NearestPlace nearest
    ) {
        public boolean hasPlace() {
            return nearest != null;
        }
    }

    public record NearestPlace(
            String kakaoPlaceId,
            String name,
            int distanceMeter,
            String roadAddress,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            String placeUrl
    ) {
    }
}
