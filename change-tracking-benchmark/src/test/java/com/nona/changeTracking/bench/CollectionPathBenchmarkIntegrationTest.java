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
 * 集合路径与标识格式化载体的装配面集成测试：在 shade 后的 {@code benchmarks.jar} 上真实运行集合变更扫描，
 * 断言四个冻结档位各产出条目、时间与分配均为正，且两次同协议运行能被 {@code CompareResultsMain} 按同一
 * 条目 key 完整配对。
 * <p>
 * 该类由 surefire 默认排除（{@code **}{@code /}{@code *IntegrationTest}），仅在 {@code -Pfull} 全量执行时
 * 运行；shade 构建与 JMH 命令由 {@link ComparisonPathBenchmarkRunFixture}（复用 T01 的
 * {@link BenchmarkRunFixture} 设施）在两个载体测试类之间共享一次。
 */
@DisplayName("CollectionPathBenchmark 装配面集成测试")
class CollectionPathBenchmarkIntegrationTest {

    /** 比对入口，从 shade 后的 jar 类路径上启动。 */
    private static final String COMPARE_MAIN_CLASS =
            "com.nona.changeTracking.bench.result.CompareResultsMain";

    /** 集合标识路径扫描的唯一测量方法。 */
    private static final String COLLECTION_METHOD =
            CollectionPathBenchmark.class.getName() + ".calculateChangesByCollectionChangeShape";

    /** 集合变更扫描的冻结档位参数名。 */
    private static final String COLLECTION_SHAPE_PARAM = "collectionChangeShape";

    /** 集合变更扫描的四个冻结档位。 */
    private static final Set<String> FROZEN_SHAPES = Set.of("0", "1", "2", "3");

    /**
     * 真实执行冻结协议一次，产出集合路径载体的归档条目。
     */
    @BeforeAll
    static void runOnTheShadedJar() {
        ComparisonPathBenchmarkRunFixture.run();
    }

    @Test
    @DisplayName("shade 后真实运行应归档集合路径载体的四个冻结档位条目，时间与分配均为正")
    void shadedRun_shouldArchiveTheFourFrozenShapesWithPositiveMetrics() {
        assertThat(ComparisonPathBenchmarkRunFixture.run().exitCode())
                .as("jmh run exit code, output tail: %s",
                        BenchmarkRunFixture.tail(ComparisonPathBenchmarkRunFixture.run().output()))
                .isZero();
        assertThat(Files.exists(ComparisonPathBenchmarkRunFixture.RESULT_FILE))
                .as("jmh result json")
                .isTrue();

        final List<BenchmarkResultEntry> entries =
                JmhResultJsonParser.parse(ComparisonPathBenchmarkRunFixture.RESULT_FILE);
        final List<BenchmarkResultEntry> pathEntries = entries.stream()
                .filter(entry -> entry.benchmark().equals(COLLECTION_METHOD))
                .toList();

        assertThat(pathEntries).as("one method times four frozen shapes").hasSize(4);
        assertThat(pathEntries).allSatisfy(entry -> {
            assertThat(entry.primaryMetric().score()).as("time of %s", entry.key()).isPositive();
            assertThat(entry.primaryMetric().scoreUnit()).as("time unit of %s", entry.key()).isEqualTo("us/op");
            assertThat(entry.allocationMetric().score()).as("allocation of %s", entry.key()).isPositive();
            assertThat(entry.allocationMetric().scoreUnit()).as("allocation unit of %s", entry.key())
                    .isEqualTo("B/op");
        });
        assertThat(pathEntries).extracting(entry -> entry.params().get(COLLECTION_SHAPE_PARAM))
                .containsExactlyInAnyOrderElementsOf(FROZEN_SHAPES);
    }

    @Test
    @DisplayName("两次同协议运行应能被 CompareResultsMain 按同一 key 完整配对")
    void entries_shouldPairByTheSameKeyWithAnOlderRunThroughCompareResultsMain() {
        assertThat(ComparisonPathBenchmarkRunFixture.pairingRun().exitCode())
                .as("pairing jmh run exit code, output tail: %s",
                        BenchmarkRunFixture.tail(ComparisonPathBenchmarkRunFixture.pairingRun().output()))
                .isZero();
        assertThat(Files.exists(ComparisonPathBenchmarkRunFixture.PAIRING_RESULT_FILE))
                .as("pairing jmh result json")
                .isTrue();
        final BenchmarkRunFixture.ProcessResult pairing = BenchmarkRunFixture.run(
                List.of("java", "-cp", BenchmarkRunFixture.BENCHMARK_JAR.toString(), COMPARE_MAIN_CLASS,
                        "--first", ComparisonPathBenchmarkRunFixture.RESULT_FILE.toString(),
                        "--second", ComparisonPathBenchmarkRunFixture.PAIRING_RESULT_FILE.toString()),
                BenchmarkRunFixture.MODULE_DIRECTORY, 300);

        assertThat(pairing.exitCode())
                .as("compare results exit code, output tail: %s", BenchmarkRunFixture.tail(pairing.output()))
                .isZero();
        assertThat(pairing.output())
                .as("the two runs pair on every entry key")
                .doesNotContain("incomparable|");
        assertThat(pairing.output()).contains(COLLECTION_METHOD);
        assertThat(pairing.output()).contains(SparseChangePathBenchmark.class.getName()
                + ".calculateChangesBySparseChangeLevel");
    }
}