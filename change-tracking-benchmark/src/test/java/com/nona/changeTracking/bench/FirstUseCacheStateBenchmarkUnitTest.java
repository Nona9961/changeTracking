package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.result.ColdSampleTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the frozen cold protocol of {@link FirstUseCacheStateBenchmark}: the single shot
 * annotation combination, the two measured first use metrics, the trial level assembly and the raw
 * sampling rows each measured invocation appends.
 * <p>
 * The raw rows are observed as the difference between the table before and after the measured call, so
 * every case stays independent of the rows other cases appended. A row is only accepted as evidence of
 * the measured operation if it carries both metrics with the time and the target allocation above
 * zero: a measured body that did nothing would append a row with an allocation of zero.
 */
@DisplayName("FirstUseCacheStateBenchmark 冷态协议单元测试")
class FirstUseCacheStateBenchmarkUnitTest {

    /**
     * Challenge string JMH requires for a directly instantiated {@link Blackhole}; the measured
     * methods are called outside the JMH harness here, so the test provides their consumer.
     */
    private static final String BLACKHOLE_CHALLENGE =
            "Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.";

    @Test
    @DisplayName("类级注解应表达冷态单次测量协议（单次模式、零预热、单迭代、batch size 1、多 fork、单线程）")
    void classAnnotations_shouldCarryTheColdSingleShotProtocol() {
        final Class<FirstUseCacheStateBenchmark> benchmark = FirstUseCacheStateBenchmark.class;

        assertThat(benchmark.getAnnotation(BenchmarkMode.class).value()).containsExactly(Mode.SingleShotTime);
        assertThat(benchmark.getAnnotation(OutputTimeUnit.class).value()).isEqualTo(TimeUnit.NANOSECONDS);
        assertThat(benchmark.getAnnotation(Warmup.class).iterations()).isZero();
        assertThat(benchmark.getAnnotation(Measurement.class).iterations()).isEqualTo(1);
        assertThat(benchmark.getAnnotation(Measurement.class).batchSize()).isEqualTo(1);
        assertThat(benchmark.getAnnotation(Fork.class).value())
                .isEqualTo(FirstUseCacheStateBenchmark.FORKS)
                .isEqualTo(5);
        assertThat(benchmark.getAnnotation(Threads.class).value()).isEqualTo(1);
        assertThat(benchmark.getAnnotation(State.class).value()).isEqualTo(Scope.Thread);
    }

    @Test
    @DisplayName("两个测量方法应是两个冷态指标，各消费一个 Blackhole")
    void measuredMethods_shouldBeTheTwoColdMetricsConsumingABlackhole() {
        final List<Method> measured = measuredMethods();

        assertThat(measured).extracting(Method::getName)
                .containsExactlyInAnyOrder("firstTargetStrategySnapshot",
                        "firstFullCalculationAfterBaselineRestore");
        assertThat(measured).allSatisfy(method -> {
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getParameterTypes()).containsExactly(Blackhole.class);
        });
    }

    @Test
    @DisplayName("装配钩子应只在 trial 级声明，测量体与复位内不得有装配钩子")
    void assemblyHook_shouldRunAtTrialLevelOnly() {
        final List<Setup> setups = Arrays.stream(FirstUseCacheStateBenchmark.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Setup.class))
                .filter(java.util.Objects::nonNull)
                .toList();

        assertThat(setups).hasSize(1);
        assertThat(setups.get(0).value()).isEqualTo(Level.Trial);
        assertThat(Arrays.stream(FirstUseCacheStateBenchmark.class.getDeclaredMethods())
                .filter(method -> method.getAnnotation(Setup.class) != null)
                .map(Method::getName)).containsExactly("setUpTrial");
    }

    @Test
    @DisplayName("trial 装配应就绪样本、手构基线与能力，且指示计量开销")
    void setUpTrial_shouldAssembleTheForkWithoutMeasuredCalls() {
        final FirstUseCacheStateBenchmark state = new FirstUseCacheStateBenchmark();

        state.setUpTrial();

        assertThat(state.sample()).isNotNull();
        assertThat(state.baseline()).isNotNull();
        assertThat(state.baseline().entities()).containsKey(state.sample());
        assertThat(state.capability()).isNotNull();
        assertThat(state.capability().getSnapshotStrategy()).isNotNull();
        assertThat(state.meteringOverheadNanos()).isNotNegative();
    }

    @Test
    @DisplayName("首次目标策略快照应追加一行带两项指标的冷态原始样本")
    void firstTargetStrategySnapshot_shouldAppendOneRawRowOfRealWork() {
        final FirstUseCacheStateBenchmark state = assembledState();
        final int rowsBefore = rowCount();

        state.firstTargetStrategySnapshot(blackhole());

        final List<ColdSampleTable.ColdSample> rows = readRows();
        assertThat(rows).hasSize(rowsBefore + 1);
        final ColdSampleTable.ColdSample row = rows.get(rows.size() - 1);
        assertThat(row.benchmark()).isEqualTo(FirstUseCacheStateBenchmark.class.getName()
                + ".firstTargetStrategySnapshot");
        assertThat(row.protocol()).isEqualTo(FirstUseCacheStateBenchmark.PROTOCOL);
        assertThat(row.params()).isEmpty();
        assertThat(row.nanosPerOperation()).isPositive();
        assertThat(row.allocatedBytesPerOperation()).isPositive();
        assertThat(row.meteringOverheadNanos()).isNotNegative();
    }

    @Test
    @DisplayName("恢复基线后的首次完整计算应追加一行带两项指标的冷态原始样本")
    void firstFullCalculationAfterBaselineRestore_shouldAppendOneRawRowOfRealWork() {
        final FirstUseCacheStateBenchmark state = assembledState();
        final int rowsBefore = rowCount();

        state.firstFullCalculationAfterBaselineRestore(blackhole());

        final List<ColdSampleTable.ColdSample> rows = readRows();
        assertThat(rows).hasSize(rowsBefore + 1);
        final ColdSampleTable.ColdSample row = rows.get(rows.size() - 1);
        assertThat(row.benchmark()).isEqualTo(FirstUseCacheStateBenchmark.class.getName()
                + ".firstFullCalculationAfterBaselineRestore");
        assertThat(row.protocol()).isEqualTo(FirstUseCacheStateBenchmark.PROTOCOL);
        assertThat(row.nanosPerOperation()).isPositive();
        assertThat(row.allocatedBytesPerOperation()).isPositive();
    }

    /**
     * Creates and assembles a fresh benchmark state; every case builds its own precondition.
     *
     * @return the assembled state of one fork
     */
    private static FirstUseCacheStateBenchmark assembledState() {
        final FirstUseCacheStateBenchmark state = new FirstUseCacheStateBenchmark();
        state.setUpTrial();
        return state;
    }

    /**
     * Creates the consumer of the measured result, as the JMH harness would.
     *
     * @return a directly instantiated blackhole
     */
    private static Blackhole blackhole() {
        return new Blackhole(BLACKHOLE_CHALLENGE);
    }

    /**
     * Returns the measured methods in declaration independent order.
     *
     * @return the benchmark methods of the class
     */
    private static List<Method> measuredMethods() {
        return Arrays.stream(FirstUseCacheStateBenchmark.class.getDeclaredMethods())
                .filter(method -> method.getAnnotation(Benchmark.class) != null)
                .sorted(Comparator.comparing(Method::getName))
                .toList();
    }

    /**
     * Reads the raw sample rows of the module result directory; a missing table counts as no row.
     *
     * @return the rows of the raw sampling table
     */
    private static List<ColdSampleTable.ColdSample> readRows() {
        final Path table = FirstUseCacheStateBenchmark.RESULT_DIRECTORY.resolve(ColdSampleTable.FILE_NAME);
        if (!Files.exists(table)) {
            return List.of();
        }
        return ColdSampleTable.read(table);
    }

    /**
     * Returns the number of raw sample rows already present.
     *
     * @return the current row count of the raw sampling table
     */
    private static int rowCount() {
        return readRows().size();
    }
}