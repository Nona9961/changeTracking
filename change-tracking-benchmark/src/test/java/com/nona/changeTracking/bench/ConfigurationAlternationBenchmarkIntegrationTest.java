package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.result.BenchmarkResultEntry;
import com.nona.changeTracking.bench.result.JmhResultJsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置交替载体的装配面集成测试：在 shade 后的 {@code benchmarks.jar} 上真实运行稳态协议，断言交替负载
 * 的唯一测量方法产出一条无参数、时间与分配均为正的归档条目，且该条目与三种热态缓存状态的条目来自同一次
 * 运行（两套配置在同一次真实运行中并存）。
 * <p>
 * 每次调用轮换配置的语义由 {@code ConfigurationAlternationBenchmarkUnitTest} 固定（复位轮换选择并解除
 * 所选基线、测量体重新登记）；本测试固定的是该载体在真实 JMH 运行中确实完成了测量体并产出可归档条目，
 * 分配为正即证明测量体内完成了快照构建与比较而不是空转。
 * <p>
 * 该类由 surefire 默认排除（{@code **}{@code /}{@code *IntegrationTest}），仅在 {@code -Pfull} 全量执行时
 * 运行；shade 构建与 JMH 命令由 {@link BenchmarkRunFixture} 在三个装配面测试类之间共享一次。
 */
@DisplayName("ConfigurationAlternationBenchmark 装配面集成测试")
class ConfigurationAlternationBenchmarkIntegrationTest {

    /** 交替负载的唯一测量方法。 */
    private static final String ALTERNATION_METHOD =
            ConfigurationAlternationBenchmark.class.getName() + ".trackAndDiffAlternatingConfigurations";

    /**
     * 真实执行稳态协议一次，产出三种热态缓存状态与配置交替载体的归档条目。
     */
    @BeforeAll
    static void runSteadyStateOnTheShadedJar() {
        BenchmarkRunFixture.steadyRun();
    }

    @Test
    @DisplayName("shade 后真实运行应归档唯一的配置交替条目，时间与分配均为正")
    void shadedSteadyRun_shouldArchiveTheAlternatingConfigurationEntry() {
        assertThat(BenchmarkRunFixture.shadedJar().exitCode())
                .as("shade build exit code, output tail: %s",
                        BenchmarkRunFixture.tail(BenchmarkRunFixture.shadedJar().output()))
                .isZero();
        assertThat(BenchmarkRunFixture.steadyRun().exitCode())
                .as("steady jmh run exit code, output tail: %s",
                        BenchmarkRunFixture.tail(BenchmarkRunFixture.steadyRun().output()))
                .isZero();
        assertThat(Files.exists(BenchmarkRunFixture.STEADY_RESULT_FILE)).as("steady jmh result json").isTrue();

        final List<BenchmarkResultEntry> entries = JmhResultJsonParser.parse(BenchmarkRunFixture.STEADY_RESULT_FILE);
        final List<BenchmarkResultEntry> alternationEntries = entries.stream()
                .filter(entry -> entry.benchmark().equals(ALTERNATION_METHOD))
                .toList();

        assertThat(alternationEntries).hasSize(1);
        final BenchmarkResultEntry alternation = alternationEntries.get(0);
        assertThat(alternation.params()).isEmpty();
        assertThat(alternation.primaryMetric().score())
                .as("the measured alternating cycle took measurable time")
                .isPositive();
        assertThat(alternation.primaryMetric().scoreUnit()).isEqualTo("us/op");
        assertThat(alternation.allocationMetric().score())
                .as("the measured alternating cycle built a snapshot and compared it")
                .isPositive();
        assertThat(alternation.allocationMetric().scoreUnit()).isEqualTo("B/op");
        assertThat(entries).extracting(BenchmarkResultEntry::benchmark)
                .as("both configuration carriers ran in the same steady protocol run")
                .anyMatch(name -> name.startsWith(CacheStateBenchmark.class.getName() + "."));
    }
}
