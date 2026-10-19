package com.zipick.housing.domain;

import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 주택 유형. 수집한 공급 호실의 주택 유형(housing_supply.housing_type) 문자열을 분류한 값이다.
 * "1형", "2형" 같은 평형 코드가 섞여 들어오므로 아래 이름에 없는 값은 '알 수 없음(null)'으로 본다.
 */
@Getter
@RequiredArgsConstructor
public enum HousingType {
    APARTMENT("아파트", List.of("아파트")),
    MULTI_HOUSEHOLD("다세대·다가구", List.of("다세대주택", "다가구주택", "다세대", "다가구")),
    ROW_HOUSE("연립주택", List.of("연립주택", "연립")),
    OFFICETEL("오피스텔", List.of("오피스텔")),
    URBAN_HOUSING("도시형생활주택", List.of("도시형생활주택"));

    private final String label;
    private final List<String> rawNames;

    public static HousingType classify(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String t = raw.trim();
        for (HousingType h : values()) {
            if (h.rawNames.contains(t)) return h;
        }
        return null;
    }
}
