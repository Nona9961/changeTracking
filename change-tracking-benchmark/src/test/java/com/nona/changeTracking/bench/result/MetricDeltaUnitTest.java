package com.nona.changeTracking.bench.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MetricDelta#between}: the difference is oriented, the threshold is the
 * larger of the two reported errors and the verdict follows AC3 (a difference inside the reported
 * error is insignificant).
 */
@DisplayName("MetricDelta 指标差异与显著性单元测试")
class MetricDeltaUnitTest {

    /** Pairing key used by these tests. */
    private static final String ENTRY_KEY = "com.nona.bench.Example[changedFieldCount=0]";

    @Test
    @DisplayName("差值超过报告误差应判为显著")
    void between_withDeltaBeyondError_shouldBeSignificant() {
        final MetricDelta delta = MetricDelta.between(
                ENTRY_KEY, BenchmarkResultComparator.PRIMARY_METRIC_NAME,
                new MetricValue(100.0, 1.0, "us/op"),
                new MetricValue(105.0, 1.0, "us/op"));

        assertThat(delta.delta()).isEqualTo(5.0);
        assertThat(delta.threshold()).isEqualTo(1.0);
        assertThat(delta.significance()).isEqualTo(Significance.SIGNIFICANT);
    }

    @Test
    @DisplayName("差值等于报告误差应判为不显著（边界）")
    void between_withDeltaEqualToError_shouldBeInsignificant() {
        final MetricDelta delta = MetricDelta.between(
                ENTRY_KEY, BenchmarkResultComparator.PRIMARY_METRIC_NAME,
                new MetricValue(100.0, 5.0, "us/op"),
                new MetricValue(105.0, 5.0, "us/op"));

        assertThat(delta.delta()).isEqualTo(5.0);
        assertThat(delta.threshold()).isEqualTo(5.0);
        assertThat(delta.significance()).isEqualTo(Significance.INSIGNIFICANT);
    }

    @Test
    @DisplayName("差值略大于报告误差应判为显著（相邻值）")
    void between_withDeltaJustBeyondError_shouldBeSignificant() {
        final MetricDelta delta = MetricDelta.between(
                ENTRY_KEY, BenchmarkResultComparator.PRIMARY_METRIC_NAME,
                new MetricValue(100.0, 5.0, "us/op"),
                new MetricValue(105.000001, 5.0, "us/op"));

        assertThat(delta.significance()).isEqualTo(Significance.SIGNIFICANT);
    }

    @Test
    @DisplayName("差值与阈值均为 0 时应判为不显著")
    void between_withZeroDeltaAndZeroThreshold_shouldBeInsignificant() {
        final MetricDelta delta = MetricDelta.between(
                ENTRY_KEY, BenchmarkResultComparator.ALLOCATION_METRIC_NAME,
                new MetricValue(0.0, 0.0, "B/op"),
                new MetricValue(0.0, 0.0, "B/op"));

        assertThat(delta.delta()).isZero();
        assertThat(delta.threshold()).isZero();
        assertThat(delta.significance()).isEqualTo(Significance.INSIGNIFICANT);
    }

    @Test
    @DisplayName("无报告误差时任意非零差值应判为显著")
    void between_withoutReportedError_shouldJudgeAnyDeltaSignificant() {
        final MetricDelta delta = MetricDelta.between(
                ENTRY_KEY, BenchmarkResultComparator.ALLOCATION_METRIC_NAME,
                new MetricValue(1024.0, 0.0, "B/op"),
                new MetricValue(1024.5, 0.0, "B/op"));

        assertThat(delta.significance()).isEqualTo(Significance.SIGNIFICANT);
    }

    @Test
    @DisplayName("阈值应取两侧报告误差的较大者")
    void between_shouldUseTheLargerReportedErrorAsThreshold() {
        final MetricDelta delta = MetricDelta.between(
                ENTRY_KEY, BenchmarkResultComparator.PRIMARY_METRIC_NAME,
                new MetricValue(100.0, 3.0, "us/op"),
                new MetricValue(105.0, 9.0, "us/op"));

        assertThat(delta.threshold()).isEqualTo(9.0);
        assertThat(delta.significance()).isEqualTo(Significance.INSIGNIFICANT);
    }

    @Test
    @DisplayName("差值方向应为第二个结果减第一个结果")
    void between_shouldOrientDeltaFromFirstToSecond() {
        final MetricDelta delta = MetricDelta.between(
                ENTRY_KEY, BenchmarkResultComparator.PRIMARY_METRIC_NAME,
                new MetricValue(105.0, 0.0, "us/op"),
                new MetricValue(100.0, 0.0, "us/op"));

        assertThat(delta.delta()).isEqualTo(-5.0);
        assertThat(delta.firstScore()).isEqualTo(105.0);
        assertThat(delta.secondScore()).isEqualTo(100.0);
        assertThat(delta.unit()).isEqualTo("us/op");
    }

    @Test
    @DisplayName("单位不一致应被拒绝（由对比层升级为整次比较拒绝）")
    void between_withDifferentUnits_shouldReject() {
        assertThatThrownBy(() -> MetricDelta.between(
                ENTRY_KEY, BenchmarkResultComparator.PRIMARY_METRIC_NAME,
                new MetricValue(100.0, 1.0, "us/op"),
                new MetricValue(0.105, 0.001, "ms/op")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("极大值相减溢出为无穷时应判为显著而不抛异常")
    void between_withOverflowingDelta_shouldBeSignificant() {
        final MetricDelta delta = MetricDelta.between(
                ENTRY_KEY, BenchmarkResultComparator.PRIMARY_METRIC_NAME,
                new MetricValue(Double.MAX_VALUE, 0.0, "ns/op"),
                new MetricValue(-Double.MAX_VALUE, 0.0, "ns/op"));

        assertThat(delta.delta()).isInfinite();
        assertThat(delta.significance()).isEqualTo(Significance.SIGNIFICANT);
    }

    @Test
    @DisplayName("null 参数应被拒绝")
    void between_withNullArguments_shouldReject() {
        final MetricValue value = new MetricValue(100.0, 1.0, "us/op");

        assertThatThrownBy(() -> MetricDelta.between(null, BenchmarkResultComparator.PRIMARY_METRIC_NAME,
                value, value))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MetricDelta.between(ENTRY_KEY, null, value, value))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MetricDelta.between(ENTRY_KEY, BenchmarkResultComparator.PRIMARY_METRIC_NAME,
                null, value))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MetricDelta.between(ENTRY_KEY, BenchmarkResultComparator.PRIMARY_METRIC_NAME,
                value, null))
                .isInstanceOf(NullPointerException.class);
    }
}