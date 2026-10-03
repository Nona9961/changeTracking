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
 * 共享子图复用基准载体的装配面集成测试：在 shade 后的 {@code benchmarks.jar} 上真实运行四个图拓扑
 * （普通树、共享、循环、混合），断言四个测量方法各按 0/1 两个冻结档位产出条目、时间与分配均为正，
 * 且两次同协议运行能被 {@code CompareResultsMain} 按同一条目 key 完整配对。
 * <p>
 * 该类由 surefire 默认排除（{@code **}{@code /}{@code *IntegrationTest}），仅在 {@code -Pfull} 全量执行时
 * 运行；shade 构建与 JMH 命令由 {@link SharedSubgraphBenchmarkRunFixture}（复用 T01 的
 * {@link BenchmarkRunFixture} 设施）提供。
 */
@DisplayName("SharedSubgraphBenchmark 装配面集成测试")
class SharedSubgraphBenchmarkIntegrationTest {

    /** 比对入口，从 shade 后的 jar 类路径上启动。 */
    private static final String COMPARE_MAIN_CLASS =
            "com.nona.changeTracking.bench.result.CompareResultsMain";

    /** 四个测量方法的全限定名。 */
    private static final List<String> BENCHMARK_METHODS = List.of(
            SharedSubgraphBenchmark.class.getName() + ".calculateChangesByPlainTreeChangeLevel",
            SharedSubgraphBenchmark.class.getName() + ".calculateChangesBySharedSubgraphChangeLevel",
            SharedSubgraphBenchmark.class.getName() + ".calculateChangesByCyclicGraphChangeLevel",
            SharedSubgraphBenchmark.class.getName() + ".calculateChangesByMixedGraphChangeLevel");

    /** 每个测量方法的冻结档位参数名，与四个扫描状态的 {@code @Param} 字段一对一。 */
    private static final Map<String, String> CHANGE_LEVEL_PARAMETERS = Map.of(
            BENCHMARK_METHODS.get(0), "plainTreeChangeLevel",
            BENCHMARK_METHODS.get(1), "sharedSubgraphChangeLevel",
            BENCHMARK_METHODS.get(2), "cyclicGraphChangeLevel",
            BENCHMARK_METHODS.get(3), "mixedGraphChangeLevel");

    /** 四个扫描状态的冻结档位取值。 */
    private static final Set<String> FROZEN_LEVELS = Set.of("0", "1");

    /**
     * 真实执行冻结协议一次，产出四个拓扑的归档条目。
     */
    @BeforeAll
    static void runOnTheShadedJar() {
        SharedSubgraphBenchmarkRunFixture.run();
    }

    @Test
    @DisplayName("shade 后真实运行应归档四个拓扑的八个档位条目，时间与分配均为正")
    void shadedRun_shouldArchiveTheFourTopologiesWithPositiveMetrics() {
        assertThat(SharedSubgraphBenchmarkRunFixture.run().exitCode())
                .as("jmh run exit code, output tail: %s",
                        BenchmarkRunFixture.tail(SharedSubgraphBenchmarkRunFixture.run().output()))
                .isZero();
        assertThat(Files.exists(SharedSubgraphBenchmarkRunFixture.RESULT_FILE))
                .as("jmh result json")
                .isTrue();

        final List<BenchmarkResultEntry> entries =
                JmhResultJsonParser.parse(SharedSubgraphBenchmarkRunFixture.RESULT_FILE);
        final List<BenchmarkResultEntry> graphEntries = entries.stream()
                .filter(entry -> entry.benchmark().startsWith(SharedSubgraphBenchmark.class.getName() + "."))
                .toList();

        assertThat(graphEntries).as("four methods times two frozen levels").hasSize(8);
        assertThat(graphEntries).allSatisfy(entry -> {
            assertThat(entry.primaryMetric().score()).as("time of %s", entry.key()).isPositive();
            assertThat(entry.primaryMetric().scoreUnit()).as("time unit of %s", entry.key()).isEqualTo("us/op");
            assertThat(entry.allocationMetric().score()).as("allocation of %s", entry.key()).isPositive();
            assertThat(entry.allocationMetric().scoreUnit()).as("allocation unit of %s", entry.key())
                    .isEqualTo("B/op");
        });
        for (final String method : BENCHMARK_METHODS) {
            assertThat(levelsOf(graphEntries, method, CHANGE_LEVEL_PARAMETERS.get(method)))
                    .as("frozen levels of %s", method)
                    .containsExactlyInAnyOrderElementsOf(FROZEN_LEVELS);
        }
    }

    @Test
    @DisplayName("两次同协议运行应能被 CompareResultsMain 按同一 key 完整配对")
    void entries_shouldPairByTheSameKeyWithAnOlderRunThroughCompareResultsMain() {
        assertThat(SharedSubgraphBenchmarkRunFixture.pairingRun().exitCode())
                .as("pairing jmh run exit code, output tail: %s",
                        BenchmarkRunFixture.tail(SharedSubgraphBenchmarkRunFixture.pairingRun().output()))
                .isZero();
        assertThat(Files.exists(SharedSubgraphBenchmarkRunFixture.PAIRING_RESULT_FILE))
                .as("pairing jmh result json")
                .isTrue();
        final BenchmarkRunFixture.ProcessResult pairing = BenchmarkRunFixture.run(
                List.of("java", "-cp", BenchmarkRunFixture.BENCHMARK_JAR.toString(), COMPARE_MAIN_CLASS,
                        "--first", SharedSubgraphBenchmarkRunFixture.RESULT_FILE.toString(),
                        "--second", SharedSubgraphBenchmarkRunFixture.PAIRING_RESULT_FILE.toString()),
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
     * 取某个方法在某一档位参数上的全部取值。
     *
     * @param entries    全部条目
     * @param methodName 目标方法的全限定名
     * @param paramName  档位参数名
     * @return 该方法在该参数上的取值集合
     */
    private static List<String> levelsOf(final List<BenchmarkResultEntry> entries, final String methodName,
                                         final String paramName) {
        return entries.stream()
                .filter(entry -> entry.benchmark().equals(methodName))
                .map(entry -> entry.params().get(paramName))
                .toList();
    }
}
