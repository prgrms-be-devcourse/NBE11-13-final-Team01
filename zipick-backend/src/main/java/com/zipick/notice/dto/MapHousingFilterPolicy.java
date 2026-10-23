package com.zipick.notice.dto;

import com.zipick.housing.dto.HouseFilter;
import com.zipick.housing.dto.HouseFilterParams;
import com.zipick.notice.domain.NoticeStatus;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 공개 지도에만 적용하는 조회 정책. 전체 공급주택 목록·추천이 쓰는 필터 기본값과 다른 부분을 여기서 덮는다.
 *
 * <p>정책이 두 가지다.
 * <ol>
 *   <li><b>모집 상태</b> — 요청한 상태와 공개 허용 상태({@link NoticeStatus#PUBLIC_STATUSES})의 교집합만 본다.
 *       기본값만 UPCOMING+OPEN 으로 두면 statuses=CLOSED 같은 요청에 마감 공고가 그대로 나간다.
 *       교집합이 비면 <b>빈 집합</b>을 그대로 넘긴다. 조회 쪽이 이를 '결과 없음'으로 처리한다
 *       (조건을 빼면 '상태 필터 없음'이 되어 전체가 나오므로 그렇게 하면 안 된다).
 *   <li><b>includeUnknown</b> — 항상 false 로 덮는다. 사용자가 고른 공급 조건에 대해
 *       값이 없는 호실을 '조건 충족'으로 보지 않는다. 요청 파라미터로 우회할 수 없다.
 *       고르지 않은 조건은 애초에 걸리지 않으므로, 값이 비어 있다는 이유로 주택이 사라지지는 않는다.
 * </ol>
 *
 * <p>전체 공급주택 목록(/api/v1/houses)과 추천의 정책은 건드리지 않는다. 그쪽은 여전히
 * 요청한 상태를 그대로 쓰고 includeUnknown 기본값이 true 다.
 */
public final class MapHousingFilterPolicy {

    private MapHousingFilterPolicy() {
    }

    public static HouseFilter apply(HouseFilterParams params) {
        HouseFilter requested = params.toFilter();
        Set<NoticeStatus> visible = requested.statuses().stream()
                .filter(NoticeStatus.PUBLIC_STATUSES::contains)
                .collect(Collectors.toUnmodifiableSet());

        return requested.toBuilder()
                .statuses(visible)
                .includeUnknown(false)
                .build();
    }
}
