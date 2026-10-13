package com.zipick.recommendation.dto;

import com.zipick.housing.dto.HouseFilterParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 추천 목록 조회 파라미터 = 공급주택 필터 + preview. */
@Data
@EqualsAndHashCode(callSuper = true)
public class RecommendationFilterParams extends HouseFilterParams {

    /**
     * true면 기준 점수·최대 개수를 적용하지 않고 후보 전체(최대 200)를 점수와 함께 돌려준다.
     * 프론트가 가중치 슬라이더를 움직일 때 서버 없이 다시 순위를 매기는 데 쓴다.
     */
    private Boolean preview = false;

    public boolean isPreview() {
        return Boolean.TRUE.equals(preview);
    }
}
