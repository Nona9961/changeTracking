package com.nona.changeTracking.bench.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ResultDiff}: the table separates significant from insignificant deltas,
 * keeps the incomparable entries visible and renders one line per element.
 */
@DisplayName("ResultDiff 差异表单元测试")
class ResultDiffUnitTest {

    /** Pairing key used by these tests. */
    private static final String ENTRY_KEY = "com.nona.bench.Example[changedFieldCount=0]";

    @Test
    @DisplayName("显著与不显著的差异应被分别筛出")
    void significantAndInsignificantDeltas_shouldFilterByVerdict() {
        final ResultDiff diff = new ResultDiff("first.json", "second.json",
                List.of(
                        delta(BenchmarkResultComparator.PRIMARY_METRIC_NAME, 5.0, Significance.SIGNIFICANT),
                        delta(BenchmarkResultComparator.ALLOCATION_METRIC_NAME, 0.5, Significance.INSIGNIFICANT)),
                List.of());

        assertThat(diff.significantDeltas()).hasSize(1);
        assertThat(diff.significantDeltas()).allSatisfy(verified -> assertThat(verified.metricName())
                .isEqualTo(BenchmarkResultComparator.PRIMARY_METRIC_NAME));
        assertThat(diff.insignificantDeltas()).hasSize(1);
        assertThat(diff.insignificantDeltas()).allSatisfy(verified -> assertThat(verified.metricName())
                .isEqualTo(BenchmarkResultComparator.ALLOCATION_METRIC_NAME));
    }

    @Test
    @DisplayName("差异表行数应等于标题、表头、差异行与不可比行之和")
    void toTable_shouldRenderOneLinePerElement() {
        final ResultDiff diff = new ResultDiff("first.json", "second.json",
                List.of(
                        delta(BenchmarkResultComparator.PRIMARY_METRIC_NAME, 5.0, Significance.SIGNIFICANT),
                        delta(BenchmarkResultComparator.ALLOCATION_METRIC_NAME, 0.5, Significance.INSIGNIFICANT)),
                List.of(ENTRY_KEY + ": only in second"));

        final String table = diff.toTable();

        assertThat(table.lines()).hasSize(2 + diff.deltas().size() + diff.incomparable().size());
        assertThat(table).contains("first.json");
        assertThat(table).contains("second.json");
        assertThat(table).contains(ENTRY_KEY);
        assertThat(table).contains("significant");
        assertThat(table).contains("insignificant");
        assertThat(table).contains("incomparable");
    }

    @Test
    @DisplayName("空差异表应仍渲染标题与表头且不抛异常")
    void toTable_withoutDeltas_shouldStillRenderHeader() {
        final ResultDiff diff = new ResultDiff("first.json", "second.json", List.of(), List.of());

        final String table = diff.toTable();

        assertThat(table.lines()).hasSize(2);
        assertThat(table).contains("first.json");
        assertThat(table).contains("second.json");
        assertThat(table).doesNotContain("incomparable");
    }

    @Test
    @DisplayName("两个列表应被防御复制，外部改动不影响差异表")
    void constructor_shouldCopyListsDefensively() {
        final List<MetricDelta> deltas = new ArrayList<>();
        deltas.add(delta(BenchmarkResultComparator.PRIMARY_METRIC_NAME, 5.0, Significance.SIGNIFICANT));
        final List<String> incomparable = new ArrayList<>();
        incomparable.add(ENTRY_KEY + ": only in first");

        final ResultDiff diff = new ResultDiff("first.json", "second.json", deltas, incomparable);
        deltas.clear();
        incomparable.clear();

        assertThat(diff.deltas()).hasSize(1);
        assertThat(diff.incomparable()).hasSize(1);
    }

    /**
     * Builds one delta with the shared entry key.
     *
     * @param metricName   name of the compared metric
     * @param delta        difference in the metric unit
     * @param significance verdict of the comparison
     * @return the delta under test
     */
    private static MetricDelta delta(final String metricName,
                                     final double delta,
                                     final Significance significance) {
        return new MetricDelta(ENTRY_KEY, metricName, "us/op", 100.0, 100.0 + delta, delta, 1.0, significance);
    }
}