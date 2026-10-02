package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.sample.SampleMutator;
import com.nona.changeTracking.bench.sample.SampleShape;
import com.nona.changeTracking.domain.model.tracking.ChangeTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the alternating configuration carrier of
 * {@link ConfigurationAlternationBenchmark}: both configurations are assembled on the frozen default
 * shape with their own sample and tracker, every measured invocation runs the other configuration, and
 * the measured body leaves the selected sample tracked again.
 * <p>
 * The alternation is asserted through the public tracking api: the per invocation reset selects the
 * other configuration and drops its baseline, and the measured body has to register that baseline
 * again, which a following field change proves. The two configurations stay independent, so a stale
 * rule of one of them cannot be observed through the other.
 */
@DisplayName("ConfigurationAlternationBenchmark 配置交替单元测试")
class ConfigurationAlternationBenchmarkUnitTest {

    /**
     * Challenge string JMH requires for a directly instantiated {@link Blackhole}; the measured
     * method is called outside the JMH harness here, so the test provides its consumer.
     */
    private static final String BLACKHOLE_CHALLENGE =
            "Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.";

    @Test
    @DisplayName("类级注解应与冻结的稳态基准约定一致")
    void classAnnotations_shouldMatchTheFrozenSteadyStateConventions() {
        final Class<ConfigurationAlternationBenchmark> benchmark = ConfigurationAlternationBenchmark.class;

        assertThat(benchmark.getAnnotation(BenchmarkMode.class).value()).containsExactly(Mode.AverageTime);
        assertThat(benchmark.getAnnotation(OutputTimeUnit.class).value()).isEqualTo(TimeUnit.MICROSECONDS);
        assertThat(benchmark.getAnnotation(Warmup.class).iterations()).isEqualTo(3);
        assertThat(benchmark.getAnnotation(Measurement.class).iterations()).isEqualTo(5);
        assertThat(benchmark.getAnnotation(Fork.class).value()).isEqualTo(1);
        assertThat(benchmark.getAnnotation(State.class).value()).isEqualTo(Scope.Thread);
    }

    @Test
    @DisplayName("唯一测量方法应消费状态与 Blackhole，并命名为交替负载")
    void measuredMethod_shouldConsumeTheStateAndABlackhole() {
        final List<Method> measured = Arrays.stream(ConfigurationAlternationBenchmark.class.getDeclaredMethods())
                .filter(method -> method.getAnnotation(Benchmark.class) != null)
                .toList();

        assertThat(measured).hasSize(1);
        final Method method = measured.get(0);
        assertThat(method.getName()).isEqualTo("trackAndDiffAlternatingConfigurations");
        assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
        assertThat(method.getParameterTypes()).containsExactly(
                ConfigurationAlternationBenchmark.AlternatingConfigurationState.class, Blackhole.class);
    }

    @Test
    @DisplayName("装配应为两个配置各建一个同形状样本与一个追踪器，并登记两份基线")
    void setUpIteration_shouldAssembleBothConfigurationsWithOwnBaselines() {
        final ConfigurationAlternationBenchmark.AlternatingConfigurationState state =
                new ConfigurationAlternationBenchmark.AlternatingConfigurationState();

        state.setUpIteration();

        assertThat(state.shape()).isEqualTo(SampleShape.defaults());
        final ChangeTracker firstTracker = state.selectedTracker();
        final Object firstSample = state.selectedSample();

        state.resetPrecondition();
        final ChangeTracker secondTracker = state.selectedTracker();
        final Object secondSample = state.selectedSample();

        assertThat(firstSample).isNotSameAs(secondSample);
        assertThat(firstTracker).isNotSameAs(secondTracker);

        SampleMutator.changeField(firstSample, "status");
        SampleMutator.changeField(secondSample, "status");

        assertThat(firstTracker.calculateChangesFor(firstSample).isEmpty())
                .as("both configurations register their own baseline during the assembly")
                .isFalse();
        assertThat(secondTracker.calculateChangesFor(secondSample).isEmpty()).isFalse();
    }

    @Test
    @DisplayName("复位应轮换配置选择，并在每次调用后重新登记所选样本的基线")
    void resetPrecondition_shouldAlternateAndTheMeasuredBodyShouldReRegisterTheBaseline() {
        final ConfigurationAlternationBenchmark benchmark = new ConfigurationAlternationBenchmark();
        final ConfigurationAlternationBenchmark.AlternatingConfigurationState state =
                new ConfigurationAlternationBenchmark.AlternatingConfigurationState();
        state.setUpIteration();

        final boolean firstSelection = state.defaultConfigurationSelected();
        state.resetPrecondition();
        final boolean secondSelection = state.defaultConfigurationSelected();
        final Object selectedSample = state.selectedSample();
        final ChangeTracker selectedTracker = state.selectedTracker();

        benchmark.trackAndDiffAlternatingConfigurations(state, blackhole());
        SampleMutator.changeField(selectedSample, "status");

        assertThat(secondSelection).isNotEqualTo(firstSelection);
        assertThat(selectedTracker.calculateChangesFor(selectedSample).isEmpty())
                .as("the measured body registers the baseline of the newly selected configuration")
                .isFalse();

        state.resetPrecondition();

        assertThat(state.defaultConfigurationSelected()).isEqualTo(firstSelection);
    }

    @Test
    @DisplayName("空状态不应被静默吞掉")
    void measuredMethod_withNullState_shouldNotBeSilentlyAccepted() {
        final ConfigurationAlternationBenchmark benchmark = new ConfigurationAlternationBenchmark();

        assertThatThrownBy(() -> benchmark.trackAndDiffAlternatingConfigurations(null, blackhole()))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * Creates the consumer of the measured result, as the JMH harness would.
     *
     * @return a directly instantiated blackhole
     */
    private static Blackhole blackhole() {
        return new Blackhole(BLACKHOLE_CHALLENGE);
    }
}