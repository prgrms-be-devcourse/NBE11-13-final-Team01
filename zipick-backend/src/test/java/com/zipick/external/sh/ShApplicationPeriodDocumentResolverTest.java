package com.zipick.external.sh;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ShApplicationPeriodDocumentResolverTest {

    private final ShApplicationPeriodDocumentResolver resolver =
            new ShApplicationPeriodDocumentResolver(null, null, null);

    @Test
    void rejectsOldLegalDateFoundInsideCurrentAnnouncement() {
        assertThat(resolver.isPlausible(
                new ShApplicationPeriod(LocalDate.of(2018, 12, 11), LocalDate.of(2018, 12, 11)),
                LocalDate.of(2026, 10, 2)
        )).isFalse();
    }

    @Test
    void acceptsApplicationPeriodNearAnnouncementDate() {
        assertThat(resolver.isPlausible(
                new ShApplicationPeriod(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 30)),
                LocalDate.of(2026, 10, 16)
        )).isTrue();
    }
}
