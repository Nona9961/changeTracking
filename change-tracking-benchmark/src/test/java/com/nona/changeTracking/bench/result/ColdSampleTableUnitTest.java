package com.nona.changeTracking.bench.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ColdSampleTable}: the raw sampling rows of the cold cache states are written
 * and read as their own artifact, a row that misses one of the two metrics never enters the table, and
 * the artifact stays outside the steady state comparison.
 * <p>
 * Every case creates its own temporary directory and leaves it behind, so a failure can be inspected
 * afterwards and no case depends on the rows another case wrote.
 */
@DisplayName("ColdSampleTable 冷态原始采样表单元测试")
class ColdSampleTableUnitTest {

    /** Protocol tag used by the cold first use carrier. */
    private static final String PROTOCOL = "first-type-use";

    @Test
    @DisplayName("写入的一行应能原样读回，字段与参数绑定保持一致")
    void appendAndRead_shouldRoundTripOneRow() throws IOException {
        final Path directory = newTableDirectory();
        final ColdSampleTable.ColdSample sample = row("com.nona.Benchmark.method",
                Map.of("changedFieldCount", "0"), 1_234.5, 4_096L, 12.5);

        ColdSampleTable.append(directory, sample);

        assertThat(ColdSampleTable.read(directory.resolve(ColdSampleTable.FILE_NAME))).containsExactly(sample);
    }

    @Test
    @DisplayName("多 fork 的多行应保留追加顺序与各自取值")
    void appendAndRead_shouldKeepEveryForkRowInOrder() throws IOException {
        final Path directory = newTableDirectory();
        final ColdSampleTable.ColdSample first = row("com.nona.Benchmark.method", Map.of(), 1_000.0, 1L, 1.0);
        final ColdSampleTable.ColdSample second = row("com.nona.Benchmark.method", Map.of(), 2_000.0, 2L, 2.0);
        final ColdSampleTable.ColdSample third =
                row("com.nona.Benchmark.other", Map.of("depth", "32"), 3_000.0, 3L, 3.0);

        ColdSampleTable.append(directory, first);
        ColdSampleTable.append(directory, second);
        ColdSampleTable.append(directory, third);

        assertThat(ColdSampleTable.read(directory.resolve(ColdSampleTable.FILE_NAME)))
                .containsExactly(first, second, third);
    }

    @Test
    @DisplayName("空表应读回空列表而不是失败")
    void read_emptyTable_shouldReturnNoRow() throws IOException {
        final Path directory = newTableDirectory();
        Files.createDirectories(directory);
        final Path table = directory.resolve(ColdSampleTable.FILE_NAME);
        Files.writeString(table, "", StandardCharsets.UTF_8);

        assertThat(ColdSampleTable.read(table)).isEmpty();
    }

    @Test
    @DisplayName("缺少或非法指标的样本应被拒绝，且不写入任何行")
    void append_incompleteRow_shouldBeRejectedWithoutWriting() throws IOException {
        final Path directory = newTableDirectory();

        assertThatThrownBy(() -> ColdSampleTable.append(directory,
                row("com.nona.Benchmark.method", Map.of(), Double.NaN, 4_096L, 1.0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ColdSampleTable.append(directory,
                row("com.nona.Benchmark.method", Map.of(), 0.0, 4_096L, 1.0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ColdSampleTable.append(directory,
                row("com.nona.Benchmark.method", Map.of(), 1_000.0, -1L, 1.0)))
                .isInstanceOf(IllegalArgumentException.class);

        final Path table = directory.resolve(ColdSampleTable.FILE_NAME);
        assertThat(Files.exists(table)).isFalse();
    }

    @Test
    @DisplayName("读取缺少指标的行应整体失败，不返回部分结果")
    void read_rowMissingAMetric_shouldBeRejected() throws IOException {
        final Path directory = newTableDirectory();
        Files.createDirectories(directory);
        final Path table = directory.resolve(ColdSampleTable.FILE_NAME);
        Files.writeString(table, "{\"benchmark\":\"com.nona.Benchmark.method\",\"params\":{},"
                        + "\"protocol\":\"" + PROTOCOL + "\",\"nanosPerOperation\":1000.0,"
                        + "\"meteringOverheadNanos\":1.0}" + System.lineSeparator(),
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ColdSampleTable.read(table)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("空白 benchmark 名或协议标签应被拒绝")
    void row_withBlankIdentity_shouldBeRejected() {
        assertThatThrownBy(() -> new ColdSampleTable.ColdSample(" ", Map.of(), PROTOCOL, 1.0, 1L, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("benchmark");
        assertThatThrownBy(() -> new ColdSampleTable.ColdSample("com.nona.Benchmark.method", Map.of(), " ", 1.0, 1L, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("protocol");
    }

    @Test
    @DisplayName("冷态原始采样表不得进入稳态比较器：JMH 结果读取与比较入口都应拒绝它")
    void coldTable_shouldStayOutsideTheSteadyStateComparison() throws IOException {
        final Path directory = newTableDirectory();
        ColdSampleTable.append(directory, row("com.nona.Benchmark.method", Map.of(), 1_000.0, 4_096L, 1.0));
        final Path table = directory.resolve(ColdSampleTable.FILE_NAME);

        assertThatThrownBy(() -> JmhResultJsonParser.parse(table)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> BenchmarkResultComparator.compare(table, table))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("缺失文件与不可写目录应显式失败")
    void ioFailures_shouldBeReported() throws IOException {
        final Path directory = newTableDirectory();
        final Path blocker = Files.createFile(directory.resolve("blocker"));

        assertThatThrownBy(() -> ColdSampleTable.read(directory.resolve("missing.jsonl")))
                .isInstanceOf(UncheckedIOException.class);
        assertThatThrownBy(() -> ColdSampleTable.append(blocker.resolve("child"),
                row("com.nona.Benchmark.method", Map.of(), 1_000.0, 1L, 0.0)))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    @DisplayName("null 目录、null 行与 null 文件应被拒绝")
    void nullArguments_shouldBeRejected() throws IOException {
        final Path directory = newTableDirectory();

        assertThatThrownBy(() -> ColdSampleTable.append(null,
                row("com.nona.Benchmark.method", Map.of(), 1.0, 1L, 0.0)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ColdSampleTable.append(directory, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ColdSampleTable.read(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ColdSampleTable.ColdSample("com.nona.Benchmark.method", null,
                PROTOCOL, 1.0, 1L, 0.0))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * Creates the isolated directory of one case; the directory is intentionally not deleted, so a
     * failure leaves its rows behind for inspection.
     *
     * @return the directory this case writes its table into
     * @throws IOException if the temporary directory cannot be created
     */
    private static Path newTableDirectory() throws IOException {
        return Files.createTempDirectory("cold-sample-table-");
    }

    /**
     * Creates one raw sample row.
     *
     * @param benchmark                 benchmark name of the row
     * @param params                    parameter binding of the row
     * @param nanosPerOperation         measured time of the target operation
     * @param allocatedBytesPerOperation measured target allocation
     * @param meteringOverheadNanos     measured metering overhead
     * @return the raw sample row
     */
    private static ColdSampleTable.ColdSample row(final String benchmark,
                                                  final Map<String, String> params,
                                                  final double nanosPerOperation,
                                                  final long allocatedBytesPerOperation,
                                                  final double meteringOverheadNanos) {
        return new ColdSampleTable.ColdSample(benchmark, params, PROTOCOL, nanosPerOperation,
                allocatedBytesPerOperation, meteringOverheadNanos);
    }
}