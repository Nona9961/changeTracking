package com.nona.changeTracking.bench.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link JmhResultJsonParser}: the JMH native result shape is read into entries, a
 * missing reported error degrades to the explicit zero, and every deviation from the shape is
 * rejected instead of being silently accepted.
 */
@DisplayName("JmhResultJsonParser 结果解析单元测试")
class JmhResultJsonParserUnitTest {

    /** Benchmark name used by these tests. */
    private static final String BENCHMARK = "com.nona.changeTracking.bench.CalculateChangesBenchmark.calculateChanges";

    @Test
    @DisplayName("单条标准条目应被完整读出（时间与分配字节数两项指标）")
    void parse_withSingleEntry_shouldReadBothMetrics(@TempDir final Path directory) throws IOException {
        final Path resultFile = resultFile(directory, document(fullEntry(BENCHMARK, 0, 91.5, 14.0, 1024.5, 12.0)));

        final List<BenchmarkResultEntry> entries = JmhResultJsonParser.parse(resultFile);

        assertThat(entries).hasSize(1);
        final BenchmarkResultEntry entry = entries.get(0);
        assertThat(entry.benchmark()).isEqualTo(BENCHMARK);
        assertThat(entry.params()).containsEntry("changedFieldCount", "0");
        assertThat(entry.primaryMetric().score()).isEqualTo(91.5);
        assertThat(entry.primaryMetric().scoreError()).isEqualTo(14.0);
        assertThat(entry.primaryMetric().scoreUnit()).isEqualTo("us/op");
        assertThat(entry.allocationMetric().score()).isEqualTo(1024.5);
        assertThat(entry.allocationMetric().scoreError()).isEqualTo(12.0);
        assertThat(entry.allocationMetric().scoreUnit()).isEqualTo("B/op");
    }

    @Test
    @DisplayName("多条条目应按文件顺序读出")
    void parse_withSeveralEntries_shouldKeepFileOrder(@TempDir final Path directory) throws IOException {
        final Path resultFile = resultFile(directory, document(
                fullEntry(BENCHMARK, 0, 91.5, 14.0, 1024.5, 12.0),
                fullEntry("com.nona.changeTracking.bench.EndToEndBenchmark.trackAndDiff", 0, 500.5, 30.0, 2048.5, 20.0)));

        final List<BenchmarkResultEntry> entries = JmhResultJsonParser.parse(resultFile);

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).benchmark()).isEqualTo(BENCHMARK);
        assertThat(entries.get(1).benchmark())
                .isEqualTo("com.nona.changeTracking.bench.EndToEndBenchmark.trackAndDiff");
    }

    @Test
    @DisplayName("空结果数组应读出空列表而非失败")
    void parse_withEmptyArray_shouldReturnEmptyList(@TempDir final Path directory) throws IOException {
        final Path resultFile = resultFile(directory, "[]");

        assertThat(JmhResultJsonParser.parse(resultFile)).isEmpty();
    }

    @Test
    @DisplayName("缺失的 scoreError 应读作显式的 0")
    void parse_withoutScoreError_shouldReadExplicitZero(@TempDir final Path directory) throws IOException {
        final String json = document("""
                {
                  "benchmark" : "%s",
                  "params" : { "changedFieldCount" : "0" },
                  "primaryMetric" : { "score" : 91.5, "scoreUnit" : "us/op" },
                  "secondaryMetrics" : {
                    "gc.alloc.rate.norm" : { "score" : 1024.5, "scoreUnit" : "B/op" }
                  }
                }""".formatted(BENCHMARK));
        final Path resultFile = resultFile(directory, json);

        final List<BenchmarkResultEntry> entries = JmhResultJsonParser.parse(resultFile);

        assertThat(entries.get(0).primaryMetric().scoreError()).isZero();
        assertThat(entries.get(0).allocationMetric().scoreError()).isZero();
    }

    @Test
    @DisplayName("数值为 0 的 scoreError 应读作 0")
    void parse_withZeroScoreError_shouldReadZero(@TempDir final Path directory) throws IOException {
        final Path resultFile = resultFile(directory, document(fullEntry(BENCHMARK, 0, 91.5, 0.0, 1024.5, 0.0)));

        final List<BenchmarkResultEntry> entries = JmhResultJsonParser.parse(resultFile);

        assertThat(entries.get(0).primaryMetric().scoreError()).isZero();
        assertThat(entries.get(0).allocationMetric().scoreError()).isZero();
    }

    @Test
    @DisplayName("缺失的 params 应读作空参数绑定")
    void parse_withoutParams_shouldReadEmptyBinding(@TempDir final Path directory) throws IOException {
        final String json = document("""
                {
                  "benchmark" : "%s",
                  "primaryMetric" : { "score" : 91.5, "scoreError" : 14.0, "scoreUnit" : "us/op" },
                  "secondaryMetrics" : {
                    "gc.alloc.rate.norm" : { "score" : 1024.5, "scoreError" : 12.0, "scoreUnit" : "B/op" }
                  }
                }""".formatted(BENCHMARK));
        final Path resultFile = resultFile(directory, json);

        final List<BenchmarkResultEntry> entries = JmhResultJsonParser.parse(resultFile);

        assertThat(entries.get(0).params()).isEmpty();
        assertThat(entries.get(0).key()).isEqualTo(BENCHMARK);
    }

    @Test
    @DisplayName("根不是数组应被拒绝")
    void parse_withObjectRoot_shouldReject(@TempDir final Path directory) throws IOException {
        final Path resultFile = resultFile(directory, "{\"benchmark\" : \"x\"}");

        assertThatThrownBy(() -> JmhResultJsonParser.parse(resultFile))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("截断的 JSON 应被拒绝")
    void parse_withTruncatedJson_shouldReject(@TempDir final Path directory) throws IOException {
        final Path resultFile = resultFile(directory, "[{\"benchmark\" : ");
        assertThatThrownBy(() -> JmhResultJsonParser.parse(resultFile))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("空文件应被拒绝")
    void parse_withEmptyFile_shouldReject(@TempDir final Path directory) throws IOException {
        final Path resultFile = resultFile(directory, "");

        assertThatThrownBy(() -> JmhResultJsonParser.parse(resultFile))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("缺失 primaryMetric 应被拒绝")
    void parse_withoutPrimaryMetric_shouldReject(@TempDir final Path directory) throws IOException {
        final String json = document("""
                {
                  "benchmark" : "%s",
                  "secondaryMetrics" : {
                    "gc.alloc.rate.norm" : { "score" : 1024.5, "scoreError" : 12.0, "scoreUnit" : "B/op" }
                  }
                }""".formatted(BENCHMARK));
        final Path resultFile = resultFile(directory, json);

        assertThatThrownBy(() -> JmhResultJsonParser.parse(resultFile))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("primaryMetric 缺失 score 应被拒绝")
    void parse_withoutPrimaryScore_shouldReject(@TempDir final Path directory) throws IOException {
        final String json = document("""
                {
                  "benchmark" : "%s",
                  "primaryMetric" : { "scoreError" : 14.0, "scoreUnit" : "us/op" },
                  "secondaryMetrics" : {
                    "gc.alloc.rate.norm" : { "score" : 1024.5, "scoreError" : 12.0, "scoreUnit" : "B/op" }
                  }
                }""".formatted(BENCHMARK));
        final Path resultFile = resultFile(directory, json);

        assertThatThrownBy(() -> JmhResultJsonParser.parse(resultFile))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("primaryMetric 缺失 scoreUnit 应被拒绝")
    void parse_withoutPrimaryUnit_shouldReject(@TempDir final Path directory) throws IOException {
        final String json = document("""
                {
                  "benchmark" : "%s",
                  "primaryMetric" : { "score" : 91.5, "scoreError" : 14.0 },
                  "secondaryMetrics" : {
                    "gc.alloc.rate.norm" : { "score" : 1024.5, "scoreError" : 12.0, "scoreUnit" : "B/op" }
                  }
                }""".formatted(BENCHMARK));
        final Path resultFile = resultFile(directory, json);

        assertThatThrownBy(() -> JmhResultJsonParser.parse(resultFile))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("缺失 gc.alloc.rate.norm（未启用分配剖析的产物）应被拒绝")
    void parse_withoutAllocationMetric_shouldReject(@TempDir final Path directory) throws IOException {
        final String json = document("""
                {
                  "benchmark" : "%s",
                  "primaryMetric" : { "score" : 91.5, "scoreError" : 14.0, "scoreUnit" : "us/op" },
                  "secondaryMetrics" : { }
                }""".formatted(BENCHMARK));
        final Path resultFile = resultFile(directory, json);

        assertThatThrownBy(() -> JmhResultJsonParser.parse(resultFile))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("缺失 benchmark 名应被拒绝")
    void parse_withoutBenchmarkName_shouldReject(@TempDir final Path directory) throws IOException {
        final String json = document("""
                {
                  "primaryMetric" : { "score" : 91.5, "scoreError" : 14.0, "scoreUnit" : "us/op" },
                  "secondaryMetrics" : {
                    "gc.alloc.rate.norm" : { "score" : 1024.5, "scoreError" : 12.0, "scoreUnit" : "B/op" }
                  }
                }""");
        final Path resultFile = resultFile(directory, json);

        assertThatThrownBy(() -> JmhResultJsonParser.parse(resultFile))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("空白 benchmark 名应被拒绝")
    void parse_withBlankBenchmarkName_shouldReject(@TempDir final Path directory) throws IOException {
        final Path resultFile = resultFile(directory, document(fullEntry("  ", 0, 91.5, 14.0, 1024.5, 12.0)));

        assertThatThrownBy(() -> JmhResultJsonParser.parse(resultFile))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("非数值 score 应被拒绝")
    void parse_withNonNumericScore_shouldReject(@TempDir final Path directory) throws IOException {
        final String json = document("""
                {
                  "benchmark" : "%s",
                  "primaryMetric" : { "score" : "fast", "scoreError" : 14.0, "scoreUnit" : "us/op" },
                  "secondaryMetrics" : {
                    "gc.alloc.rate.norm" : { "score" : 1024.5, "scoreError" : 12.0, "scoreUnit" : "B/op" }
                  }
                }""".formatted(BENCHMARK));
        final Path resultFile = resultFile(directory, json);

        assertThatThrownBy(() -> JmhResultJsonParser.parse(resultFile))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("结果文件不存在应报告读取失败")
    void parse_withMissingFile_shouldReportReadFailure(@TempDir final Path directory) {
        final Path missing = directory.resolve(JmhResultJsonParser.RESULT_FILE_NAME);

        assertThatThrownBy(() -> JmhResultJsonParser.parse(missing))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    @DisplayName("结果路径是目录应报告读取失败")
    void parse_withDirectoryPath_shouldReportReadFailure(@TempDir final Path directory) throws IOException {
        final Path asDirectory = directory.resolve(JmhResultJsonParser.RESULT_FILE_NAME);
        Files.createDirectory(asDirectory);

        assertThatThrownBy(() -> JmhResultJsonParser.parse(asDirectory))
                .isInstanceOf(UncheckedIOException.class);
    }

    /**
     * Writes the given JSON document into the temporary directory.
     *
     * @param directory the temporary directory of the test
     * @param content   the JSON document
     * @return the path of the written result file
     * @throws IOException if the file cannot be written
     */
    private static Path resultFile(final Path directory, final String content) throws IOException {
        final Path file = directory.resolve(JmhResultJsonParser.RESULT_FILE_NAME);
        Files.writeString(file, content);
        return file;
    }

    /**
     * Wraps the given entries into a JSON array document.
     *
     * @param entries the JSON entry documents
     * @return the JSON array document
     */
    private static String document(final String... entries) {
        return "[" + String.join(",", entries) + "]";
    }

    /**
     * Renders one standard JMH result entry carrying both archived metrics.
     *
     * @param benchmark       the benchmark name
     * @param changedFieldCount the scanned parameter value
     * @param score           the primary metric score
     * @param scoreError      the primary metric error
     * @param allocation      the allocation metric score
     * @param allocationError the allocation metric error
     * @return the JSON entry document
     */
    private static String fullEntry(final String benchmark,
                                    final int changedFieldCount,
                                    final double score,
                                    final double scoreError,
                                    final double allocation,
                                    final double allocationError) {
        return """
                {
                  "jmhVersion" : "1.37",
                  "benchmark" : "%s",
                  "mode" : "avgt",
                  "params" : { "changedFieldCount" : "%s" },
                  "primaryMetric" : { "score" : %s, "scoreError" : %s, "scoreUnit" : "us/op" },
                  "secondaryMetrics" : {
                    "gc.alloc.rate.norm" : { "score" : %s, "scoreError" : %s, "scoreUnit" : "B/op" }
                  }
                }""".formatted(benchmark, changedFieldCount, score, scoreError, allocation, allocationError);
    }
}