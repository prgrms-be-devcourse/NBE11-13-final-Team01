package com.zipick.notice.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AddressNormalizerTest {

    @Test
    void 원본_표현_차이를_제거해_중복비교용_주소를_만든다() {
        assertThat(AddressNormalizer.canonicalize("강원도 강릉시 강변북길 259 (송정동)"))
                .isEqualTo("강원특별자치도강릉시강변북길259");
    }

    @Test
    void 전체주소에서_도로명과_법정동을_분리한다() {
        String address = "강원특별자치도 강릉시 강변북길 259(송정동)";

        assertThat(AddressNormalizer.extractRoadName(address)).isEqualTo("강변북길");
        assertThat(AddressNormalizer.extractLegalDongName(address)).isEqualTo("송정동");
        assertThat(AddressNormalizer.extractBuildingName(address)).isNull();
    }

    @Test
    void 괄호가_건물명이면_건물명으로_사용한다() {
        assertThat(AddressNormalizer.extractBuildingName("충청남도 공주시 우금티로 709-12 (공주옥룡1)"))
                .isEqualTo("공주옥룡1");
    }
}
