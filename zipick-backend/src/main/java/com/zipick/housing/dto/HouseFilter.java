package com.zipick.housing.dto;

import com.zipick.housing.domain.Agency;
import com.zipick.housing.domain.AnnouncementStatus;
import com.zipick.housing.domain.HousingType;
import com.zipick.housing.domain.RentalType;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Builder;

/**
 * 공급주택 필터 조건. 추천 후보를 조건으로 거를 때 쓴다.
 *
 * <p>값이 null/빈 목록이면 그 조건은 적용하지 않는다.
 * includeUnknown=true 이면 해당 필터 값이 없는(null) 집도 포함한다.
 */
@Builder(toBuilder = true)
public record HouseFilter(
        Set<String> sigunguCodes,
        Integer depositMin,
        Integer depositMax,
        Integer rentMin,
        Integer rentMax,
        BigDecimal areaMin,
        BigDecimal areaMax,
        Set<HousingType> housingTypes,
        Set<AnnouncementStatus> statuses,
        Set<Agency> agencies,
        Set<RentalType> rentalTypes,
        boolean includeUnknown
) {

    /** 기본값: 모집 중(공고중·접수중)인 집 전체 */
    public static HouseFilter openOnly() {
        return HouseFilter.builder()
                .statuses(Set.of(AnnouncementStatus.NOTICE, AnnouncementStatus.RECEIVING))
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
