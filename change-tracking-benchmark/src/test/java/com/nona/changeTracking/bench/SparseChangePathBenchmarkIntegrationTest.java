package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.result.BenchmarkResultEntry;
import com.nona.changeTracking.bench.result.JmhResultJsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 稀疏变更与深链路径载体的装配面集成测试：在 shade 后的 {@code benchmarks.jar} 上真实运行两个扫描状态，
 * 断言两个测量方法各按 0/1 两个冻结档位产出条目，时间与分配均为正。
 * <p>
 * 该类由 surefire 默认排除（{@code **}{@code /}{@code *IntegrationTest}），仅在 {@code -Pfull} 全量执行时
 * 运行；shade 构建与 JMH 命令由 {@link ComparisonPathBenchmarkRunFixture}（复用 T01 的
 * {@link BenchmarkRunFixture} 设施）在两个载体测试类之间共享一次。
 */
@DisplayName("SparseChangePathBenchmark 装配面集成测试")
class SparseChangePathBenchmarkIntegrationTest {

    /** 默认形状稀疏变更扫描的唯一测量方法。 */
    private static final String SPARSE_METHOD =
            SparseChangePathBenchmark.class.getName() + ".calculateChangesBySparseChangeLevel";

    /** 深链稀疏变更扫描的唯一测量方法。 */
    private static final String DEEP_CHAIN_METHOD =
            SparseChangePathBenchmark.class.getName() + ".calculateChangesByDeepChainChangeLevel";

    /** 稀疏变更扫描的冻结档位参数名。 */
    private static final String SPARSE_LEVEL_PARAM = "sparseChangeLevel";

    /** 深链扫描的冻结档位参数名。 */
    private static final String DEEP_CHAIN_LEVEL_PARAM = "deepChainChangeLevel";

    /** 两个扫描状态的冻结档位取值。 */
    private static final Set<String> FROZEN_LEVELS = Set.of("0", "1");

    /**
     * 真实执行冻结协议一次，产出两个扫描状态的归档条目。
     */
    @BeforeAll
    static void runOnTheShadedJar() {
        ComparisonPathBenchmarkRunFixture.run();
    }

    @Test
    @DisplayName("shade 后真实运行应归档两个扫描状态的四个档位条目，时间与分配均为正")
    void shadedRun_shouldArchiveTheTwoScanStatesWithPositiveMetrics() {
        assertThat(ComparisonPathBenchmarkRunFixture.run().exitCode())
                .as("jmh run exit code, output tail: %s",
                        BenchmarkRunFixture.tail(ComparisonPathBenchmarkRunFixture.run().output()))
                .isZero();
        assertThat(Files.exists(ComparisonPathBenchmarkRunFixture.RESULT_FILE))
                .as("jmh result json")
                .isTrue();

        final List<BenchmarkResultEntry> entries =
                JmhResultJsonParser.parse(ComparisonPathBenchmarkRunFixture.RESULT_FILE);
        final List<BenchmarkResultEntry> scanEntries = entries.stream()
                .filter(entry -> entry.benchmark().startsWith(SparseChangePathBenchmark.class.getName() + "."))
                .toList();

        assertThat(scanEntries).as("two methods times two frozen levels").hasSize(4);
        assertThat(scanEntries).allSatisfy(entry -> {
            assertThat(entry.primaryMetric().score()).as("time of %s", entry.key()).isPositive();
            assertThat(entry.primaryMetric().scoreUnit()).as("time unit of %s", entry.key()).isEqualTo("us/op");
            assertThat(entry.allocationMetric().score()).as("allocation of %s", entry.key()).isPositive();
            assertThat(entry.allocationMetric().scoreUnit()).as("allocation unit of %s", entry.key())
                    .isEqualTo("B/op");
        });
        assertThat(levelsOf(scanEntries, SPARSE_METHOD, SPARSE_LEVEL_PARAM)).containsExactlyInAnyOrderElementsOf(FROZEN_LEVELS);
        assertThat(levelsOf(scanEntries, DEEP_CHAIN_METHOD, DEEP_CHAIN_LEVEL_PARAM))
                .containsExactlyInAnyOrderElementsOf(FROZEN_LEVELS);
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
