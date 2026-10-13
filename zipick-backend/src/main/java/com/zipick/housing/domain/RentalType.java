package com.zipick.housing.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 공공임대 유형. 실제 공급 위치가 정해진 유형만 다룬다 (전세임대는 제외). */
@Getter
@RequiredArgsConstructor
public enum RentalType {
    PURCHASE("매입임대"),
    HAPPY("행복주택"),
    NATIONAL("국민임대"),
    DEUNDEUN("든든전세");

    private final String label;
}
