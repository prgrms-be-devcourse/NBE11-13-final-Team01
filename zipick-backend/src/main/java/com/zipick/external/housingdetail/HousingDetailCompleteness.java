package com.zipick.external.housingdetail;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** 같은 문서의 공급 행 중 일부에만 핵심 값이 있는 경우를 탐지한다. */
public final class HousingDetailCompleteness {

    private HousingDetailCompleteness() {
    }

    public static List<String> partialCoreFields(List<DetailedHousingUnit> units) {
        if (units == null || units.size() < 2) return List.of();
        List<String> result = new ArrayList<>();
        addIfPartial(result, "rentDeposit", units, HousingDetailCompleteness::hasDeposit);
        addIfPartial(result, "monthlyRent", units, HousingDetailCompleteness::hasMonthlyRent);
        addIfPartial(result, "exclusiveArea", units, unit -> unit.exclusiveArea() != null);
        return List.copyOf(result);
    }

    private static boolean hasDeposit(DetailedHousingUnit unit) {
        return unit.rentDeposit() != null
                || unit.rentalConditions().stream().anyMatch(condition -> condition.rentDeposit() != null);
    }

    private static boolean hasMonthlyRent(DetailedHousingUnit unit) {
        return unit.monthlyRent() != null
                || unit.rentalConditions().stream().anyMatch(condition -> condition.monthlyRent() != null);
    }

    private static void addIfPartial(
            List<String> result,
            String field,
            List<DetailedHousingUnit> units,
            Predicate<DetailedHousingUnit> present
    ) {
        long count = units.stream().filter(present).count();
        if (count > 0 && count < units.size()) {
            result.add(field + "=" + count + "/" + units.size());
        }
    }
}
