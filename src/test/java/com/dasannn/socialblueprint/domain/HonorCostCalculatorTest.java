package com.dasannn.socialblueprint.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HonorCostCalculatorTest {
    @Test void balanceBasedQuote() {
        var calculator = new HonorCostCalculator(HonorCostConfig.defaults());
        assertThat(calculator.calculateCost(0)).isEqualTo(30);
        assertThat(calculator.calculateCost(-100)).isEqualTo(30);
        assertThat(calculator.calculateCost(10_000)).isEqualTo(830);
        assertThat(new HonorCostCalculator(new HonorCostConfig(10.333, 1.5)).calculateCost(10)).isEqualTo(10.48);
        assertThat(new HonorCostCalculator(new HonorCostConfig(0, 8)).calculateCost(100)).isEqualTo(8);
        assertThat(new HonorCostCalculator(new HonorCostConfig(30, 0)).calculateCost(10_000)).isEqualTo(30);
    }
    @Test void rejectsNonFiniteBalancesAndOverflow() {
        var calculator = new HonorCostCalculator(HonorCostConfig.defaults());
        assertThatThrownBy(() -> calculator.calculateCost(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HonorCostCalculator(new HonorCostConfig(Double.MAX_VALUE, 100))
                .calculateCost(Double.MAX_VALUE)).isInstanceOf(ArithmeticException.class);
    }
}
