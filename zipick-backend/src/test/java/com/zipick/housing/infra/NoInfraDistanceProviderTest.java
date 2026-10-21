package com.zipick.housing.infra;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NoInfraDistanceProviderTest {

    @Test
    void returnsEmptyDistances() {
        assertThat(new NoInfraDistanceProvider().nearest(List.of())).isEmpty();
    }
}
