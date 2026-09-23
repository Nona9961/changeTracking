package com.nona.changeTracking.bench.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BenchmarkResultEntry}: the pairing key is derived from the content (so the
 * JSON field order cannot break the pairing), the parameter binding is copied and the metrics stay
 * attached to the entry.
 */
@DisplayName("BenchmarkResultEntry 结果条目单元测试")
class BenchmarkResultEntryUnitTest {

    /** Benchmark name used by these tests. */
    private static final String BENCHMARK = "com.nona.changeTracking.bench.CalculateChangesBenchmark.calculateChanges";

    @Test
    @DisplayName("配对键应包含基准名与按字典序排列的参数")
    void key_withMultipleParams_shouldRenderParamsInLexicographicOrder() {
        final Map<String, String> params = new LinkedHashMap<>();
        params.put("changedFieldCount", "1");
        params.put("collectionSize", "100");

        final BenchmarkResultEntry entry = entry(params);

        assertThat(entry.key()).isEqualTo(BENCHMARK + "[changedFieldCount=1,collectionSize=100]");
    }

    @Test
    @DisplayName("无参数条目的配对键应只有基准名")
    void key_withoutParams_shouldBeTheBenchmarkName() {
        final BenchmarkResultEntry entry = entry(Map.of());

        assertThat(entry.key()).isEqualTo(BENCHMARK);
    }

    @Test
    @DisplayName("参数书写顺序不同应得到相同配对键")
    void key_withReorderedParams_shouldBeStable() {
        final Map<String, String> firstOrder = new LinkedHashMap<>();
        firstOrder.put("changedFieldCount", "1");
        firstOrder.put("collectionSize", "100");
        final Map<String, String> secondOrder = new LinkedHashMap<>();
        secondOrder.put("collectionSize", "100");
        secondOrder.put("changedFieldCount", "1");

        assertThat(entry(firstOrder).key()).isEqualTo(entry(secondOrder).key());
    }

    @Test
    @DisplayName("参数绑定应被防御复制，外部改动不影响条目")
    void constructor_withMutableParams_shouldCopyDefensively() {
        final Map<String, String> params = new HashMap<>();
        params.put("changedFieldCount", "1");

        final BenchmarkResultEntry entry = entry(params);
        params.put("changedFieldCount", "20");

        assertThat(entry.params()).containsEntry("changedFieldCount", "1");
    }

    @Test
    @DisplayName("基准名与两项指标应保真")
    void constructor_withFullEntry_shouldKeepBenchmarkAndMetrics() {
        final BenchmarkResultEntry entry = new BenchmarkResultEntry(
                BENCHMARK,
                Map.of("changedFieldCount", "0"),
                new MetricValue(91.5, 14.0, "us/op"),
                new MetricValue(1024.5, 12.0, "B/op"));

        assertThat(entry.benchmark()).isEqualTo(BENCHMARK);
        assertThat(entry.primaryMetric().scoreUnit()).isEqualTo("us/op");
        assertThat(entry.allocationMetric().score()).isEqualTo(1024.5);
        assertThat(entry.allocationMetric().scoreUnit()).isEqualTo("B/op");
    }

    @Test
    @DisplayName("空白基准名应被拒绝")
    void constructor_withBlankBenchmark_shouldReject() {
        assertThatThrownBy(() -> new BenchmarkResultEntry(
                " ", Map.of(), new MetricValue(1.0, 0.0, "us/op"), new MetricValue(1.0, 0.0, "B/op")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null 基准名应被拒绝")
    void constructor_withNullBenchmark_shouldReject() {
        assertThatThrownBy(() -> new BenchmarkResultEntry(
                null, Map.of(), new MetricValue(1.0, 0.0, "us/op"), new MetricValue(1.0, 0.0, "B/op")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Builds an entry with the shared benchmark name and the given parameter binding.
     *
     * @param params the parameter binding of the entry
     * @return the entry under test
     */
    private static BenchmarkResultEntry entry(final Map<String, String> params) {
        return new BenchmarkResultEntry(
                BENCHMARK, params, new MetricValue(91.5, 14.0, "us/op"), new MetricValue(1024.5, 12.0, "B/op"));
    }
}