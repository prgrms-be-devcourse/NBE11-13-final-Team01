package com.zipick.external.sh;

import java.time.LocalDate;

record ShApplicationPeriod(LocalDate startDate, LocalDate endDate) {

    boolean isNotExpiredOn(LocalDate date) {
        return !endDate.isBefore(date);
    }
}
