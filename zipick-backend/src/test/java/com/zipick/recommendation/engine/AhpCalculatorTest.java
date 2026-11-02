package com.zipick.recommendation.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.zipick.global.error.BusinessException;
import com.zipick.recommendation.engine.AhpCalculator.Comparison;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AhpCalculatorTest {

    private final AhpCalculator ahp = new AhpCalculator();

    @Test
    @DisplayName("전부 '비슷해요'면 가중치가 같고 우선순위는 모두 3")
    void allEqual() {
        var r = ahp.calculate(List.of(
                new Comparison("cost", "region", 0),
                new Comparison("region", "infra", 0),
                new Comparison("cost", "infra", 0)));

        assertThat(r.weights().values()).allSatisfy(w -> assertThat(w).isCloseTo(1 / 3.0, within(1e-3)));
        assertThat(r.consistencyRatio()).isZero();
        assertThat(PriorityConverter.fromRatios(r.weights())).containsValues(3, 3, 3);
    }

    @Test
    @DisplayName("비용이 훨씬 중요하다고 답하면 비용 우선순위 5, 나머지는 낮아진다")
    void costFirst() {
        var r = ahp.calculate(List.of(
                new Comparison("cost", "region", 2),
                new Comparison("region", "infra", 0),
                new Comparison("cost", "infra", 2)));

        assertThat(r.weights().get("cost")).isGreaterThan(0.7);
        assertThat(r.consistent()).isTrue();
        Map<String, Integer> p = PriorityConverter.fromRatios(r.weights());
        assertThat(p.get("cost")).isEqualTo(5);
        assertThat(p.get("region")).isEqualTo(p.get("infra")).isLessThan(3);
    }

    @Test
    @DisplayName("답이 순환하면(비용>지역>인프라>비용) 엇갈림으로 판정, 세 항목 가중치는 같아진다")
    void inconsistent() {
        var r = ahp.calculate(List.of(
                new Comparison("cost", "region", 2),
                new Comparison("region", "infra", 2),
                new Comparison("cost", "infra", -2)));

        assertThat(r.consistent()).isFalse();
        assertThat(r.consistencyRatio()).isGreaterThan(AhpCalculator.CONSISTENCY_LIMIT);
        assertThat(r.weights().values()).allSatisfy(w -> assertThat(w).isCloseTo(1 / 3.0, within(1e-3)));
    }

    @Test
    @DisplayName("'훨씬'만 이어서 답해도 순서가 맞으면 엇갈림이 아니다 (5단계 척도 한계로 CR은 0.1을 넘음)")
    void strongChainIsConsistent() {
        var r = ahp.calculate(List.of(
                new Comparison("cost", "region", 2),
                new Comparison("region", "infra", 2),
                new Comparison("cost", "infra", 2)));

        assertThat(r.consistent()).isTrue();
        assertThat(r.consistencyRatio()).isGreaterThan(AhpCalculator.CONSISTENCY_LIMIT);
    }

    @Test
    @DisplayName("비용>지역, 지역=인프라인데 인프라>비용이면 엇갈림")
    void equalityViolation() {
        var r = ahp.calculate(List.of(
                new Comparison("cost", "region", 1),
                new Comparison("region", "infra", 0),
                new Comparison("cost", "infra", -1)));

        assertThat(r.consistent()).isFalse();
    }

    @Test
    @DisplayName("일관된 답(비용 3배 지역, 지역 3배 인프라, 비용 5배 인프라)은 통과")
    void consistentChain() {
        var r = ahp.calculate(List.of(
                new Comparison("cost", "region", 1),
                new Comparison("region", "infra", 1),
                new Comparison("cost", "infra", 2)));

        assertThat(r.consistent()).isTrue();
        assertThat(r.weights().get("cost")).isGreaterThan(r.weights().get("region"));
        assertThat(r.weights().get("region")).isGreaterThan(r.weights().get("infra"));
    }

    @Test
    @DisplayName("비교한 항목의 모든 쌍에 답하지 않으면 거부")
    void incomplete() {
        assertThatThrownBy(() -> ahp.calculate(List.of(
                new Comparison("cost", "region", 1),
                new Comparison("region", "infra", 1))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("Saaty 척도 변환")
    void saaty() {
        assertThat(AhpCalculator.saaty(2)).isEqualTo(5);
        assertThat(AhpCalculator.saaty(1)).isEqualTo(3);
        assertThat(AhpCalculator.saaty(0)).isEqualTo(1);
        assertThat(AhpCalculator.saaty(-1)).isCloseTo(1 / 3.0, within(1e-9));
    }
}
