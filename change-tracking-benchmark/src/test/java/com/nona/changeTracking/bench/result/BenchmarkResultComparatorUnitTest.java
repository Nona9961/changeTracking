package com.nona.changeTracking.bench.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BenchmarkResultComparator}: entries pair on content (name plus parameters),
 * both archived metrics are compared with a verdict, entries present on one side only are listed as
 * incomparable, and two values of one metric that do not share a unit reject the whole comparison
 * instead of being translated or silently skipped.
 */
@DisplayName("BenchmarkResultComparator 结果对比单元测试")
class BenchmarkResultComparatorUnitTest {

    /** Benchmark name used by these tests. */
    private static final String BENCHMARK = "com.nona.changeTracking.bench.CalculateChangesBenchmark.calculateChanges";

    /** Second benchmark name used by these tests. */
    private static final String OTHER_BENCHMARK = "com.nona.changeTracking.bench.EndToEndBenchmark.trackAndDiff";

    @Test
    @DisplayName("同一条目的两项指标应各自给出显著性判定")
    void compare_withOnePairedEntry_shouldJudgeBothMetrics(@TempDir final Path directory) throws IOException {
        final Path first = resultFile(directory, "first.json", entry(BENCHMARK, "0", "us/op", 100.0, 1.0, 1024.0, 500.0));
        final Path second = resultFile(directory, "second.json", entry(BENCHMARK, "0", "us/op", 150.0, 1.0, 1024.5, 500.0));

        final ResultDiff diff = BenchmarkResultComparator.compare(first, second);

        assertThat(diff.deltas()).hasSize(2);
        assertThat(diff.incomparable()).isEmpty();
        assertThat(diff.deltas())
                .anySatisfy(delta -> {
                    assertThat(delta.metricName()).isEqualTo(BenchmarkResultComparator.PRIMARY_METRIC_NAME);
                    assertThat(delta.significance()).isEqualTo(Significance.SIGNIFICANT);
                })
                .anySatisfy(delta -> {
                    assertThat(delta.metricName()).isEqualTo(BenchmarkResultComparator.ALLOCATION_METRIC_NAME);
                    assertThat(delta.significance()).isEqualTo(Significance.INSIGNIFICANT);
                });
        assertThat(diff.significantDeltas()).hasSize(1);
        assertThat(diff.insignificantDeltas()).hasSize(1);
    }

    @Test
    @DisplayName("两次完全相同的运行应全部判为不显著")
    void compare_withIdenticalResults_shouldJudgeEverythingInsignificant(@TempDir final Path directory) throws IOException {
        final String document = entry(BENCHMARK, "0", "us/op", 100.0, 1.0, 1024.0, 500.0);
        final Path first = resultFile(directory, "first.json", document);
        final Path second = resultFile(directory, "second.json", document);

        final ResultDiff diff = BenchmarkResultComparator.compare(first, second);

        assertThat(diff.deltas()).isNotEmpty();
        assertThat(diff.significantDeltas()).isEmpty();
        assertThat(diff.insignificantDeltas()).hasSameSizeAs(diff.deltas());
    }

    @Test
    @DisplayName("参数书写顺序不同应仍能配对")
    void compare_withReorderedParams_shouldStillPair(@TempDir final Path directory) throws IOException {
        final Path first = resultFile(directory, "first.json", """
                {
                  "benchmark" : "%s",
                  "params" : { "changedFieldCount" : "0", "collectionSize" : "100" },
                  "primaryMetric" : { "score" : 100.0, "scoreError" : 1.0, "scoreUnit" : "us/op" },
                  "secondaryMetrics" : {
                    "gc.alloc.rate.norm" : { "score" : 1024.0, "scoreError" : 500.0, "scoreUnit" : "B/op" }
                  }
                }""".formatted(BENCHMARK));
        final Path second = resultFile(directory, "second.json", """
                {
                  "benchmark" : "%s",
                  "params" : { "collectionSize" : "100", "changedFieldCount" : "0" },
                  "primaryMetric" : { "score" : 150.0, "scoreError" : 1.0, "scoreUnit" : "us/op" },
                  "secondaryMetrics" : {
                    "gc.alloc.rate.norm" : { "score" : 1024.5, "scoreError" : 500.0, "scoreUnit" : "B/op" }
                  }
                }""".formatted(BENCHMARK));

        final ResultDiff diff = BenchmarkResultComparator.compare(first, second);

        assertThat(diff.incomparable()).isEmpty();
        assertThat(diff.deltas()).hasSize(2);
        assertThat(diff.deltas()).allSatisfy(delta -> assertThat(delta.entryKey())
                .isEqualTo(BENCHMARK + "[changedFieldCount=0,collectionSize=100]"));
    }

    @Test
    @DisplayName("每一条配对条目都应产出两项指标差异")
    void compare_withSeveralPairedEntries_shouldCompareEach(@TempDir final Path directory) throws IOException {
        final Path first = resultFile(directory, "first.json",
                entry(BENCHMARK, "0", "us/op", 100.0, 1.0, 1024.0, 500.0),
                entry(OTHER_BENCHMARK, "0", "us/op", 500.0, 10.0, 2048.0, 100.0));
        final Path second = resultFile(directory, "second.json",
                entry(BENCHMARK, "0", "us/op", 150.0, 1.0, 1024.5, 500.0),
                entry(OTHER_BENCHMARK, "0", "us/op", 520.0, 10.0, 2048.5, 100.0));

        final ResultDiff diff = BenchmarkResultComparator.compare(first, second);

        assertThat(diff.deltas()).hasSize(4);
        assertThat(diff.deltas()).extracting(MetricDelta::entryKey)
                .containsOnly(BENCHMARK + "[changedFieldCount=0]", OTHER_BENCHMARK + "[changedFieldCount=0]");
    }

    @Test
    @DisplayName("仅一侧存在的条目应列入不可比并注明来源，其余条目照常比较")
    void compare_withEntryOnOneSideOnly_shouldReportItIncomparable(@TempDir final Path directory) throws IOException {
        final Path first = resultFile(directory, "first.json",
                entry(BENCHMARK, "0", "us/op", 100.0, 1.0, 1024.0, 500.0),
                entry(OTHER_BENCHMARK, "0", "us/op", 500.0, 10.0, 2048.0, 100.0));
        final Path second = resultFile(directory, "second.json",
                entry(BENCHMARK, "0", "us/op", 150.0, 1.0, 1024.5, 500.0));

        final ResultDiff diff = BenchmarkResultComparator.compare(first, second);

        assertThat(diff.deltas()).hasSize(2);
        assertThat(diff.incomparable()).singleElement()
                .satisfies(description -> assertThat(description)
                        .contains(OTHER_BENCHMARK)
                        .contains("only in first"));
    }

    @Test
    @DisplayName("两侧条目集合完全无交集时应无可比差异并列出全部条目")
    void compare_withoutIntersection_shouldHaveNoDeltas(@TempDir final Path directory) throws IOException {
        final Path first = resultFile(directory, "first.json", entry(BENCHMARK, "0", "us/op", 100.0, 1.0, 1024.0, 500.0));
        final Path second = resultFile(directory, "second.json", entry(OTHER_BENCHMARK, "0", "us/op", 100.0, 1.0, 1024.0, 500.0));

        final ResultDiff diff = BenchmarkResultComparator.compare(first, second);

        assertThat(diff.deltas()).isEmpty();
        assertThat(diff.incomparable()).hasSize(2);
        assertThat(diff.incomparable())
                .anySatisfy(description -> assertThat(description).contains("only in first"))
                .anySatisfy(description -> assertThat(description).contains("only in second"));
    }

    @Test
    @DisplayName("主指标单位不一致应拒绝整次比较并说明两侧单位")
    void compare_withPrimaryUnitMismatch_shouldRejectTheWholeComparison(@TempDir final Path directory) throws IOException {
        final Path first = resultFile(directory, "first.json", entry(BENCHMARK, "0", "us/op", 100.0, 1.0, 1024.0, 500.0));
        final Path second = resultFile(directory, "second.json", entry(BENCHMARK, "0", "ms/op", 0.1, 0.001, 1024.5, 500.0));

        assertThatThrownBy(() -> BenchmarkResultComparator.compare(first, second))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(BENCHMARK + "[changedFieldCount=0]")
                .hasMessageContaining(BenchmarkResultComparator.PRIMARY_METRIC_NAME)
                .hasMessageContaining("scoreUnit mismatch")
                .hasMessageContaining("us/op")
                .hasMessageContaining("ms/op");
    }

    @Test
    @DisplayName("分配指标单位不一致应拒绝整次比较（另一项指标可比也不放行）")
    void compare_withAllocationUnitMismatch_shouldRejectTheWholeComparison(@TempDir final Path directory)
            throws IOException {
        final Path first = resultFile(directory, "first.json", entry(BENCHMARK, "0", "us/op", 100.0, 1.0, 1024.0, 500.0));
        final Path second = resultFile(directory, "second.json", """
                {
                  "benchmark" : "%s",
                  "params" : { "changedFieldCount" : "0" },
                  "primaryMetric" : { "score" : 100.0, "scoreError" : 1.0, "scoreUnit" : "us/op" },
                  "secondaryMetrics" : {
                    "gc.alloc.rate.norm" : { "score" : 1024.0, "scoreError" : 500.0, "scoreUnit" : "byte/op" }
                  }
                }""".formatted(BENCHMARK));

        assertThatThrownBy(() -> BenchmarkResultComparator.compare(first, second))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(BENCHMARK + "[changedFieldCount=0]")
                .hasMessageContaining(BenchmarkResultComparator.ALLOCATION_METRIC_NAME)
                .hasMessageContaining("scoreUnit mismatch")
                .hasMessageContaining("B/op")
                .hasMessageContaining("byte/op");
    }

    @Test
    @DisplayName("两项指标单位都一致时应照常比较（相邻情形）")
    void compare_withMatchingUnits_shouldCompareBothMetrics(@TempDir final Path directory) throws IOException {
        final Path first = resultFile(directory, "first.json", entry(BENCHMARK, "0", "us/op", 100.0, 1.0, 1024.0, 500.0));
        final Path second = resultFile(directory, "second.json", entry(BENCHMARK, "0", "us/op", 150.0, 1.0, 1024.5, 500.0));

        final ResultDiff diff = BenchmarkResultComparator.compare(first, second);

        assertThat(diff.deltas()).hasSize(2);
        assertThat(diff.incomparable()).isEmpty();
    }

    @Test
    @DisplayName("单侧结果为空应被拒绝（不能静默产出空表）")
    void compare_withEmptyFirstResult_shouldReject(@TempDir final Path directory) throws IOException {
        final Path first = resultFile(directory, "first.json", "[]");
        final Path second = resultFile(directory, "second.json", entry(BENCHMARK, "0", "us/op", 100.0, 1.0, 1024.0, 500.0));

        assertThatThrownBy(() -> BenchmarkResultComparator.compare(first, second))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("两侧结果都为空应被拒绝")
    void compare_withBothResultsEmpty_shouldReject(@TempDir final Path directory) throws IOException {
        final Path first = resultFile(directory, "first.json", "[]");
        final Path second = resultFile(directory, "second.json", "[]");

        assertThatThrownBy(() -> BenchmarkResultComparator.compare(first, second))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("结果文件不存在应报告读取失败")
    void compare_withMissingFile_shouldReportReadFailure(@TempDir final Path directory) throws IOException {
        final Path first = resultFile(directory, "first.json", entry(BENCHMARK, "0", "us/op", 100.0, 1.0, 1024.0, 500.0));
        final Path missing = directory.resolve("second.json");

        assertThatThrownBy(() -> BenchmarkResultComparator.compare(first, missing))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    @DisplayName("差异表应以两份结果路径作为标签")
    void compare_shouldLabelTheTwoSidesWithTheirPaths(@TempDir final Path directory) throws IOException {
        final Path first = resultFile(directory, "first.json", entry(BENCHMARK, "0", "us/op", 100.0, 1.0, 1024.0, 500.0));
        final Path second = resultFile(directory, "second.json", entry(BENCHMARK, "0", "us/op", 100.0, 1.0, 1024.0, 500.0));

        final ResultDiff diff = BenchmarkResultComparator.compare(first, second);

        assertThat(diff.firstLabel()).contains("first.json");
        assertThat(diff.secondLabel()).contains("second.json");
    }

    /**
     * Writes a JMH result document into the temporary directory.
     *
     * @param directory the temporary directory of the test
     * @param name      the file name
     * @param entries   the JSON entry documents
     * @return the path of the written result file
     * @throws IOException if the file cannot be written
     */
    private static Path resultFile(final Path directory, final String name, final String... entries) throws IOException {
        final Path file = directory.resolve(name);
        Files.writeString(file, "[" + String.join(",", entries) + "]");
        return file;
    }

    /**
     * Renders one JMH result entry carrying both archived metrics.
     *
     * @param benchmark       the benchmark name
     * @param changedFieldCount the scanned parameter value
     * @param primaryUnit     the unit of the primary metric
     * @param score           the primary metric score
     * @param scoreError      the primary metric error
     * @param allocation      the allocation metric score
     * @param allocationError the allocation metric error
     * @return the JSON entry document
     */
    private static String entry(final String benchmark,
                                final String changedFieldCount,
                                final String primaryUnit,
                                final double score,
                                final double scoreError,
                                final double allocation,
                                final double allocationError) {
        return """
                {
                  "benchmark" : "%s",
                  "params" : { "changedFieldCount" : "%s" },
                  "primaryMetric" : { "score" : %s, "scoreError" : %s, "scoreUnit" : "%s" },
                  "secondaryMetrics" : {
                    "gc.alloc.rate.norm" : { "score" : %s, "scoreError" : %s, "scoreUnit" : "B/op" }
                  }
                }""".formatted(benchmark, changedFieldCount, score, scoreError, primaryUnit, allocation, allocationError);
    }
}