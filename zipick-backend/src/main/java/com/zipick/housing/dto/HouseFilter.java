package com.zipick.housing.dto;

import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import com.zipick.notice.domain.NoticeStatus;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Builder;

/**
 * 공급주택 필터 조건. 금액은 만 원, 면적은 ㎡.
 *
 * <p>값이 null/빈 목록이면 그 조건은 적용하지 않는다.
 * includeUnknown=true 이면 해당 필터 값이 없는(null) 호실도 포함한다.
 * regions 는 "시도 시군구" 키다 (예: "경상북도 구미시").
 */
@Builder(toBuilder = true)
public record HouseFilter(
        Set<String> regions,
        Integer depositMin,
        Integer depositMax,
        Integer rentMin,
        Integer rentMax,
        BigDecimal areaMin,
        BigDecimal areaMax,
        Set<HousingType> housingTypes,
        Set<NoticeStatus> statuses,
        Set<String> agencies,
        Set<RentalType> rentalTypes,
        boolean includeUnknown,
        /** 이 공고들만 (새 공고 알림의 역매칭용). null 이면 전체 */
        Set<Long> noticeIds
) {

    /** 모집 상태 '전체' = 공고 예정 + 접수 중 */
    public static final Set<NoticeStatus> OPEN_STATUSES = Set.of(NoticeStatus.UPCOMING, NoticeStatus.OPEN);

    /** 기본값: 모집 중(공고 예정·접수 중)인 집 전체 */
    public static HouseFilter openOnly() {
        return HouseFilter.builder()
                .statuses(OPEN_STATUSES)
                .includeUnknown(true)
                .build();
    }

    public static <E extends Enum<E>> Set<E> parseEnums(List<String> raw, Class<E> type) {
        return parseStrings(raw).stream()
                .map(s -> Enum.valueOf(type, s.toUpperCase()))
                .collect(Collectors.toSet());
    }

    public static Set<String> parseStrings(List<String> raw) {
        if (raw == null || raw.isEmpty()) return Set.of();
        return raw.stream()
                .flatMap(s -> Arrays.stream(s.split(",")))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
