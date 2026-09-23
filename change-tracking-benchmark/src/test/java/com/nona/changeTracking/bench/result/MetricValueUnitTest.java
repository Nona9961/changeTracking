package com.nona.changeTracking.bench.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MetricValue}: the unit is mandatory, the error has an explicit zero
 * representation and is never negative, the score may be any double.
 */
@DisplayName("MetricValue 指标值单元测试")
class MetricValueUnitTest {

    @Test
    @DisplayName("正常指标值应保真")
    void constructor_withMeasuredValue_shouldKeepAllFields() {
        final MetricValue value = new MetricValue(91.17358531374842, 14.372949556403777, "us/op");

        assertThat(value.score()).isEqualTo(91.17358531374842);
        assertThat(value.scoreError()).isEqualTo(14.372949556403777);
        assertThat(value.scoreUnit()).isEqualTo("us/op");
    }

    @Test
    @DisplayName("误差为 0 应被接受（产物未携带报告误差）")
    void constructor_withZeroError_shouldBeAccepted() {
        final MetricValue value = new MetricValue(1024.5, 0.0, "B/op");

        assertThat(value.scoreError()).isZero();
    }

    @Test
    @DisplayName("分数为 0 应被接受（合法的零分配或零耗时）")
    void constructor_withZeroScore_shouldBeAccepted() {
        final MetricValue value = new MetricValue(0.0, 0.0, "B/op");

        assertThat(value.score()).isZero();
    }

    @Test
    @DisplayName("极大分数应被接受（不因数值范围拒绝）")
    void constructor_withLargestScore_shouldBeAccepted() {
        final MetricValue value = new MetricValue(Double.MAX_VALUE, 0.0, "ns/op");

        assertThat(value.score()).isEqualTo(Double.MAX_VALUE);
    }

    @Test
    @DisplayName("单位为 null 应被拒绝")
    void constructor_withNullUnit_shouldReject() {
        assertThatThrownBy(() -> new MetricValue(1.0, 0.0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("单位为空白应被拒绝")
    void constructor_withBlankUnit_shouldReject() {
        assertThatThrownBy(() -> new MetricValue(1.0, 0.0, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("负误差应被拒绝")
    void constructor_withNegativeError_shouldReject() {
        assertThatThrownBy(() -> new MetricValue(1.0, -0.5, "us/op"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("NaN 误差应被拒绝")
    void constructor_withNaNError_shouldReject() {
        assertThatThrownBy(() -> new MetricValue(1.0, Double.NaN, "us/op"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}