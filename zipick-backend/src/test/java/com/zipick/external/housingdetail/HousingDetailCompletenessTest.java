package com.zipick.external.housingdetail;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HousingDetailCompletenessTest {

    @Test
    void detectsCoreFieldsMissingFromOnlySomeRows() {
        DetailedHousingUnit complete = unit(10_000_000L, 200_000L, new BigDecimal("30.5"));
        DetailedHousingUnit missing = unit(null, null, null);

        assertThat(HousingDetailCompleteness.partialCoreFields(List.of(complete, missing)))
                .containsExactly("rentDeposit=1/2", "monthlyRent=1/2", "exclusiveArea=1/2");
    }

    @Test
    void doesNotReportAFieldThatTheDocumentDoesNotProvideAtAll() {
        assertThat(HousingDetailCompleteness.partialCoreFields(List.of(
                unit(null, null, null), unit(null, null, null)
        ))).isEmpty();
    }

    private DetailedHousingUnit unit(Long deposit, Long rent, BigDecimal area) {
        return new DetailedHousingUnit(
                "key", null, null, null, "서울특별시 중구 세종대로 1",
                null, null, null, area, null, null, null, deposit, rent, null
        );
    }
}
