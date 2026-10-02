package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.result.BenchmarkResultEntry;
import com.nona.changeTracking.bench.result.JmhResultJsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三种热态缓存状态载体的装配面集成测试：在 shade 后的 {@code benchmarks.jar} 上真实运行稳态协议，断言
 * 四个测量方法各产出一条无参数、时间与分配均为正的归档条目，且这些条目（连同同批的配置交替条目）能被
 * {@code CompareResultsMain} 按内容派生的条目 key 与旧运行完整配对。
 * <p>
 * 该类由 surefire 默认排除（{@code **}{@code /}{@code *IntegrationTest}），仅在 {@code -Pfull} 全量执行时
 * 运行；shade 构建与 JMH 命令由 {@link BenchmarkRunFixture} 在三个装配面测试类之间共享一次。
 */
@DisplayName("CacheStateBenchmark 装配面集成测试")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CacheStateBenchmarkIntegrationTest {

    /** 比对入口，从 shade 后的 jar 类路径上启动。 */
    private static final String COMPARE_MAIN_CLASS =
            "com.nona.changeTracking.bench.result.CompareResultsMain";

    /** 冻结的三个热态缓存状态对应的四个测量方法。 */
    private static final Set<String> EXPECTED_CACHE_STATE_METHODS = Set.of(
            CacheStateBenchmark.class.getName() + ".snapshotByReusedCapability",
            CacheStateBenchmark.class.getName() + ".calculateChangesByReusedCapability",
            CacheStateBenchmark.class.getName() + ".calculateChangesByNewTrackerReusingCapability",
            CacheStateBenchmark.class.getName() + ".calculateChangesByNewTrackerAndCapability");

    /**
     * 真实执行稳态协议一次，产出三种热态缓存状态的归档条目。
     */
    @BeforeAll
    static void runSteadyStateOnTheShadedJar() {
        BenchmarkRunFixture.steadyRun();
        BenchmarkRunFixture.steadyPairingRun();
    }

    @Test
    @Order(1)
    @DisplayName("shade 后真实运行应归档三个热态缓存状态的四条无参数条目，时间与分配均为正")
    void shadedSteadyRun_shouldArchiveTheThreeWarmStatesWithPositiveMetrics() {
        assertThat(BenchmarkRunFixture.shadedJar().exitCode())
                .as("shade build exit code, output tail: %s",
                        BenchmarkRunFixture.tail(BenchmarkRunFixture.shadedJar().output()))
                .isZero();
        assertThat(BenchmarkRunFixture.steadyRun().exitCode())
                .as("steady jmh run exit code, output tail: %s",
                        BenchmarkRunFixture.tail(BenchmarkRunFixture.steadyRun().output()))
                .isZero();
        assertThat(Files.exists(BenchmarkRunFixture.STEADY_RESULT_FILE)).as("steady jmh result json").isTrue();

        final List<BenchmarkResultEntry> cacheStateEntries = JmhResultJsonParser.parse(BenchmarkRunFixture.STEADY_RESULT_FILE)
                .stream()
                .filter(entry -> entry.benchmark().startsWith(CacheStateBenchmark.class.getName() + "."))
                .toList();

        assertThat(cacheStateEntries).hasSize(EXPECTED_CACHE_STATE_METHODS.size());
        assertThat(cacheStateEntries).extracting(BenchmarkResultEntry::benchmark)
                .containsExactlyInAnyOrderElementsOf(EXPECTED_CACHE_STATE_METHODS);
        assertThat(cacheStateEntries).allSatisfy(entry -> {
            assertThat(entry.params()).as("params of %s", entry.benchmark()).isEmpty();
            assertThat(entry.primaryMetric().score()).as("time of %s", entry.benchmark()).isPositive();
            assertThat(entry.primaryMetric().scoreUnit()).as("time unit of %s", entry.benchmark()).isEqualTo("us/op");
            assertThat(entry.allocationMetric().score()).as("allocation of %s", entry.benchmark()).isPositive();
            assertThat(entry.allocationMetric().scoreUnit()).as("allocation unit of %s", entry.benchmark())
                    .isEqualTo("B/op");
        });
    }

    @Test
    @Order(2)
    @DisplayName("热态条目与配置交替条目应能被 CompareResultsMain 按同一 key 与旧运行完整配对")
    void steadyEntries_shouldPairByTheSameKeyWithAnOlderRunThroughCompareResultsMain() {
        assertThat(BenchmarkRunFixture.steadyPairingRun().exitCode())
                .as("pairing jmh run exit code, output tail: %s",
                        BenchmarkRunFixture.tail(BenchmarkRunFixture.steadyPairingRun().output()))
                .isZero();
        assertThat(Files.exists(BenchmarkRunFixture.STEADY_PAIRING_RESULT_FILE))
                .as("pairing jmh result json")
                .isTrue();
        final BenchmarkRunFixture.ProcessResult pairing = BenchmarkRunFixture.run(
                List.of("java", "-cp", BenchmarkRunFixture.BENCHMARK_JAR.toString(), COMPARE_MAIN_CLASS,
                        "--first", BenchmarkRunFixture.STEADY_RESULT_FILE.toString(),
                        "--second", BenchmarkRunFixture.STEADY_PAIRING_RESULT_FILE.toString()),
                BenchmarkRunFixture.MODULE_DIRECTORY, 300);

        assertThat(pairing.exitCode())
                .as("compare results exit code, output tail: %s", BenchmarkRunFixture.tail(pairing.output()))
                .isZero();
        assertThat(pairing.output())
                .as("the two runs pair on every entry key")
                .doesNotContain("incomparable|");
        assertThat(pairing.output()).contains(EXPECTED_CACHE_STATE_METHODS.toArray(String[]::new));
        assertThat(pairing.output())
                .contains(ConfigurationAlternationBenchmark.class.getName() + ".trackAndDiffAlternatingConfigurations");
    }
}
