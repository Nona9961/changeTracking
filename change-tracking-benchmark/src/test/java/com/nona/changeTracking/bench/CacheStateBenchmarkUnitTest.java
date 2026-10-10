package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.sample.SampleMutator;
import com.nona.changeTracking.bench.sample.SampleOrder;
import com.nona.changeTracking.bench.sample.SampleShape;
import com.nona.changeTracking.tracking.ChangeTracker;
import com.nona.changeTracking.tracking.TrackingCapabilityProvider;
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
 * Unit tests for the three warm cache states of {@link CacheStateBenchmark}: the steady state
 * annotation combination, the one state class per measured operation pairing, the warm assembly of
 * every state and the measured behaviour each state must produce.
 * <p>
 * The behaviour assertions use the public tracking api of the state under test: a measured snapshot
 * has to leave the sample tracked (otherwise the following field change would not be reported) and a
 * measured calculation must not advance the baseline (otherwise the restored baseline semantics of the
 * steady state carrier would be wrong). The two new tracker states additionally have to create a new
 * tracker (and, in the new capability state, a new capability) per invocation.
 */
@DisplayName("CacheStateBenchmark 稳态缓存状态单元测试")
class CacheStateBenchmarkUnitTest {

    /**
     * Challenge string JMH requires for a directly instantiated {@link Blackhole}; the measured
     * methods are called outside the JMH harness here, so the test provides their consumer.
     */
    private static final String BLACKHOLE_CHALLENGE =
            "Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.";

    @Test
    @DisplayName("类级注解应与冻结的稳态基准约定一致")
    void classAnnotations_shouldMatchTheFrozenSteadyStateConventions() {
        final Class<CacheStateBenchmark> benchmark = CacheStateBenchmark.class;

        assertThat(benchmark.getAnnotation(BenchmarkMode.class).value()).containsExactly(Mode.AverageTime);
        assertThat(benchmark.getAnnotation(OutputTimeUnit.class).value()).isEqualTo(TimeUnit.MICROSECONDS);
        assertThat(benchmark.getAnnotation(Warmup.class).iterations()).isEqualTo(3);
        assertThat(benchmark.getAnnotation(Measurement.class).iterations()).isEqualTo(5);
        assertThat(benchmark.getAnnotation(Fork.class).value()).isEqualTo(1);
        assertThat(benchmark.getAnnotation(State.class).value()).isEqualTo(Scope.Thread);
    }

    @Test
    @DisplayName("四个测量方法应各对应一个状态类并消费 Blackhole")
    void measuredMethods_shouldPairOneStateClassEachAndConsumeTheResult() {
        final List<Method> measured = measuredMethods();

        assertThat(measured).extracting(Method::getName).containsExactly(
                "calculateChangesByNewTrackerAndCapability",
                "calculateChangesByNewTrackerReusingCapability",
                "calculateChangesByReusedCapability",
                "snapshotByReusedCapability");
        assertThat(measured).allSatisfy(method -> {
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getParameterTypes()).hasSize(2);
            assertThat(method.getParameterTypes()[1]).isEqualTo(Blackhole.class);
            assertThat(method.getParameterTypes()[0].getDeclaringClass())
                    .isEqualTo(CacheStateBenchmark.class);
            assertThat(Modifier.isStatic(method.getParameterTypes()[0].getModifiers())).isTrue();
        });
        assertThat(measured).extracting(method -> method.getParameterTypes()[0])
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("能力提供者应经公开扩展点发现默认能力")
    void capabilityProvider_shouldDiscoverTheDefaultCapability() {
        final TrackingCapabilityProvider provider = CacheStateBenchmark.capabilityProvider();

        assertThat(provider.getName()).isEqualTo(CacheStateBenchmark.DEFAULT_PROVIDER_NAME);
        assertThat(provider.create()).isNotNull();
    }

    @Test
    @DisplayName("同 capability 复用的快照测量：装配后复位，测量后样本应重新被追踪")
    void snapshotByReusedCapability_shouldLeaveTheSampleTracked() {
        final CacheStateBenchmark benchmark = new CacheStateBenchmark();
        final CacheStateBenchmark.ReusedCapabilitySnapshotState state =
                new CacheStateBenchmark.ReusedCapabilitySnapshotState();
        state.setUpIteration();

        assertThat(state.sample()).isNotNull();
        assertThat(state.tracker()).isNotNull();

        state.resetPrecondition();
        benchmark.snapshotByReusedCapability(state, blackhole());
        SampleMutator.changeField(state.sample(), "status");

        assertThat(state.tracker().calculateChangesFor(state.sample()).isEmpty())
                .as("the measured track has to register the baseline again")
                .isFalse();
    }

    @Test
    @DisplayName("同 capability 复用的完整计算：装配登记基线，计算不推进基线")
    void calculateChangesByReusedCapability_shouldKeepTheBaselineRegistered() {
        final CacheStateBenchmark benchmark = new CacheStateBenchmark();
        final CacheStateBenchmark.ReusedCapabilityCalculationState state =
                new CacheStateBenchmark.ReusedCapabilityCalculationState();
        state.setUpIteration();

        benchmark.calculateChangesByReusedCapability(state, blackhole());
        SampleMutator.changeField(state.sample(), "status");

        assertThat(state.tracker().calculateChangesFor(state.sample()).isEmpty())
                .as("the assembly baseline stays in place and the calculation must not advance it")
                .isFalse();
    }

    @Test
    @DisplayName("新 tracker 复用 capability：每次调用应创建新 tracker 且不修改基线")
    void calculateChangesByNewTrackerReusingCapability_shouldCreateATrackerPerInvocation() {
        final CacheStateBenchmark benchmark = new CacheStateBenchmark();
        final CacheStateBenchmark.ReusedCapabilityNewTrackerState state =
                new CacheStateBenchmark.ReusedCapabilityNewTrackerState();
        state.setUpIteration();

        assertThat(state.capability()).isNotNull();
        assertThat(state.sample()).isNotNull();
        assertThat(state.baseline()).isNotNull();
        assertThat(state.baseline().entities()).containsKey(state.sample());
        assertThat(state.lastTracker()).as("a tracker exists only after a measured invocation").isNull();

        benchmark.calculateChangesByNewTrackerReusingCapability(state, blackhole());
        final ChangeTracker firstTracker = state.lastTracker();
        benchmark.calculateChangesByNewTrackerReusingCapability(state, blackhole());

        assertThat(firstTracker).isNotNull();
        assertThat(state.lastTracker()).isNotSameAs(firstTracker);
        assertThat(firstTracker.calculateChanges().isEmpty())
                .as("the untouched sample reports the zero change case")
                .isTrue();
    }

    @Test
    @DisplayName("新 tracker 新 capability：每次调用应创建新 tracker 与新 capability")
    void calculateChangesByNewTrackerAndCapability_shouldCreateBothPerInvocation() {
        final CacheStateBenchmark benchmark = new CacheStateBenchmark();
        final CacheStateBenchmark.NewCapabilityState state = new CacheStateBenchmark.NewCapabilityState();
        state.setUpIteration();

        assertThat(state.sample()).isNotNull();
        assertThat(state.baseline()).isNotNull();
        assertThat(state.lastCapability()).as("a capability exists only after a measured invocation").isNull();
        assertThat(state.lastTracker()).isNull();

        benchmark.calculateChangesByNewTrackerAndCapability(state, blackhole());
        final Object firstCapability = state.lastCapability();
        final ChangeTracker firstTracker = state.lastTracker();
        benchmark.calculateChangesByNewTrackerAndCapability(state, blackhole());

        assertThat(firstCapability).isNotNull();
        assertThat(firstTracker).isNotNull();
        assertThat(state.lastCapability()).isNotSameAs(firstCapability);
        assertThat(state.lastTracker()).isNotSameAs(firstTracker);
        assertThat(firstTracker.calculateChanges().isEmpty())
                .as("the untouched sample reports the zero change case")
                .isTrue();
    }

    @Test
    @DisplayName("装配样本应为冻结默认形状，且空状态测量不静默吞掉")
    void assemblyShapeAndNullState_shouldFollowTheFrozenContract() {
        final CacheStateBenchmark benchmark = new CacheStateBenchmark();
        final CacheStateBenchmark.ReusedCapabilitySnapshotState state =
                new CacheStateBenchmark.ReusedCapabilitySnapshotState();
        state.setUpIteration();

        final SampleOrder sample = (SampleOrder) state.sample();
        assertThat(sample.items()).hasSize(SampleShape.DEFAULT_COLLECTION_SIZE);
        assertThatThrownBy(() -> benchmark.calculateChangesByReusedCapability(null, blackhole()))
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

    /**
     * Returns the measured methods in name order.
     *
     * @return the benchmark methods of the class
     */
    private static List<Method> measuredMethods() {
        return Arrays.stream(CacheStateBenchmark.class.getDeclaredMethods())
                .filter(method -> method.getAnnotation(Benchmark.class) != null)
                .sorted(java.util.Comparator.comparing(Method::getName))
                .toList();
    }
}