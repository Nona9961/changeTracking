package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.result.BenchmarkResultEntry;
import com.nona.changeTracking.bench.result.JmhResultJsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 视图投影基准载体的装配面集成测试：在 shade 后的 {@code benchmarks.jar} 上真实运行四个载体
 * （完整视图、叶子视图、重复获取、含视图消费的完整链路），断言四个测量方法各按 0/1 两个冻结档位产出
 * 条目、时间与分配均为正，且两次同协议运行能被 {@code CompareResultsMain} 按同一条目 key 完整配对。
 * <p>
 * 该类由 surefire 默认排除（{@code **}{@code /}{@code *IntegrationTest}），仅在 {@code -Pfull} 全量执行时
 * 运行；shade 构建与 JMH 命令由 {@link ViewProjectionBenchmarkRunFixture}（复用 {@link BenchmarkRunFixture}
 * 设施）提供。
 */
@DisplayName("ViewProjectionBenchmark 装配面集成测试")
class ViewProjectionBenchmarkIntegrationTest {

    /** 比对入口，从 shade 后的 jar 类路径上启动。 */
    private static final String COMPARE_MAIN_CLASS =
            "com.nona.changeTracking.bench.result.CompareResultsMain";

    /** 四个测量方法的全限定名。 */
    private static final List<String> BENCHMARK_METHODS = List.of(
            ViewProjectionBenchmark.class.getName() + ".projectAllChangesByViewChangeLevel",
            ViewProjectionBenchmark.class.getName() + ".projectLeafChangesByViewChangeLevel",
            ViewProjectionBenchmark.class.getName() + ".projectAllChangesRepeatedlyByViewChangeLevel",
            ViewProjectionBenchmark.class.getName() + ".calculateChangesAndProjectByViewChangeLevel");

    /** 四个扫描状态共用的冻结档位参数名，与每个扫描状态的 {@code @Param} 字段一对一。 */
    private static final String CHANGE_LEVEL_PARAMETER = "viewChangeLevel";

    /** 四个扫描状态的冻结档位取值。 */
    private static final Set<String> FROZEN_LEVELS = Set.of("0", "1");

    /**
     * 真实执行冻结协议一次，产出四个载体的归档条目。
     */
    @BeforeAll
    static void runOnTheShadedJar() {
        ViewProjectionBenchmarkRunFixture.run();
    }

    @Test
    @DisplayName("shade 后真实运行应归档四个载体的八个档位条目，时间与分配均为正")
    void shadedRun_shouldArchiveTheFourCarriersWithPositiveMetrics() {
        assertThat(ViewProjectionBenchmarkRunFixture.run().exitCode())
                .as("jmh run exit code, output tail: %s",
                        BenchmarkRunFixture.tail(ViewProjectionBenchmarkRunFixture.run().output()))
                .isZero();
        assertThat(Files.exists(ViewProjectionBenchmarkRunFixture.RESULT_FILE))
                .as("jmh result json")
                .isTrue();

        final List<BenchmarkResultEntry> entries =
                JmhResultJsonParser.parse(ViewProjectionBenchmarkRunFixture.RESULT_FILE);
        final List<BenchmarkResultEntry> viewEntries = entries.stream()
                .filter(entry -> entry.benchmark().startsWith(ViewProjectionBenchmark.class.getName() + "."))
                .toList();

        assertThat(viewEntries).as("four methods times two frozen levels").hasSize(8);
        assertThat(viewEntries).allSatisfy(entry -> {
            assertThat(entry.primaryMetric().score()).as("time of %s", entry.key()).isPositive();
            assertThat(entry.primaryMetric().scoreUnit()).as("time unit of %s", entry.key()).isEqualTo("us/op");
            assertThat(entry.allocationMetric().score()).as("allocation of %s", entry.key()).isPositive();
            assertThat(entry.allocationMetric().scoreUnit()).as("allocation unit of %s", entry.key())
                    .isEqualTo("B/op");
        });
        for (final String method : BENCHMARK_METHODS) {
            assertThat(levelsOf(viewEntries, method))
                    .as("frozen levels of %s", method)
                    .containsExactlyInAnyOrderElementsOf(FROZEN_LEVELS);
        }
    }

    @Test
    @DisplayName("两次同协议运行应能被 CompareResultsMain 按同一 key 完整配对")
    void entries_shouldPairByTheSameKeyWithAnOlderRunThroughCompareResultsMain() {
        assertThat(ViewProjectionBenchmarkRunFixture.pairingRun().exitCode())
                .as("pairing jmh run exit code, output tail: %s",
                        BenchmarkRunFixture.tail(ViewProjectionBenchmarkRunFixture.pairingRun().output()))
                .isZero();
        assertThat(Files.exists(ViewProjectionBenchmarkRunFixture.PAIRING_RESULT_FILE))
                .as("pairing jmh result json")
                .isTrue();
        final BenchmarkRunFixture.ProcessResult pairing = BenchmarkRunFixture.run(
                List.of("java", "-cp", BenchmarkRunFixture.BENCHMARK_JAR.toString(), COMPARE_MAIN_CLASS,
                        "--first", ViewProjectionBenchmarkRunFixture.RESULT_FILE.toString(),
                        "--second", ViewProjectionBenchmarkRunFixture.PAIRING_RESULT_FILE.toString()),
                BenchmarkRunFixture.MODULE_DIRECTORY, 300);

        assertThat(pairing.exitCode())
                .as("compare results exit code, output tail: %s", BenchmarkRunFixture.tail(pairing.output()))
                .isZero();
        assertThat(pairing.output())
                .as("the two runs pair on every entry key")
                .doesNotContain("incomparable|");
        for (final String method : BENCHMARK_METHODS) {
            assertThat(pairing.output()).contains(method);
        }
    }

    /**
     * 取某个方法在冻结档位参数上的全部取值。
     *
     * @param entries    全部条目
     * @param methodName 目标方法的全限定名
     * @return 该方法在该参数上的取值集合
     */
    private static List<String> levelsOf(final List<BenchmarkResultEntry> entries, final String methodName) {
        return entries.stream()
                .filter(entry -> entry.benchmark().equals(methodName))
                .map(entry -> entry.params().get(CHANGE_LEVEL_PARAMETER))
                .toList();
    }
}
