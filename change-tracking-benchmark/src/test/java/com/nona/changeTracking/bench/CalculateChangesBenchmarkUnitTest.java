package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.sample.SampleShape;
import com.nona.changeTracking.domain.model.tracking.ChangeTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the frozen parameterization pattern as applied by {@link CalculateChangesBenchmark}:
 * the class level annotation combination, the two independent single dimension scan states and their
 * frozen levels, the one scan state per benchmark method pairing with its naming rule, the iteration
 * assembly hook without a per invocation reset, and the measured bodies keeping the precondition
 * assembled by the scan state.
 * <p>
 * The scan states are the only place this path differs from {@link SnapshotBuildBenchmark}: the
 * change detection path assembles its precondition at iteration level and needs no per invocation
 * reset, because the measured comparison leaves the sample unchanged.
 */
@DisplayName("CalculateChangesBenchmark 参数化模式单元测试")
class CalculateChangesBenchmarkUnitTest {

    /**
     * Challenge string JMH requires for a directly instantiated {@link Blackhole}; the measured
     * methods are called outside the JMH harness here, so the test provides their consumer.
     */
    private static final String BLACKHOLE_CHALLENGE =
            "Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.";

    @Test
    @DisplayName("类级注解应与冻结的基准约定一致")
    void classAnnotations_shouldMatchTheFrozenConventions() {
        final BenchmarkMode mode = CalculateChangesBenchmark.class.getAnnotation(BenchmarkMode.class);
        final Warmup warmup = CalculateChangesBenchmark.class.getAnnotation(Warmup.class);
        final Measurement measurement = CalculateChangesBenchmark.class.getAnnotation(Measurement.class);

        assertThat(mode.value()).containsExactly(Mode.AverageTime);
        assertThat(CalculateChangesBenchmark.class.getAnnotation(OutputTimeUnit.class).value())
                .isEqualTo(TimeUnit.MICROSECONDS);
        assertThat(warmup.iterations()).isEqualTo(3);
        assertThat(warmup.time()).isEqualTo(1);
        assertThat(measurement.iterations()).isEqualTo(5);
        assertThat(measurement.time()).isEqualTo(1);
        assertThat(CalculateChangesBenchmark.class.getAnnotation(Fork.class).value()).isEqualTo(1);
        assertThat(CalculateChangesBenchmark.class.getAnnotation(State.class).value()).isEqualTo(Scope.Thread);
    }

    @Test
    @DisplayName("两个扫描状态应为共享扫描基类的具体状态，可直接装配")
    void scanStates_shouldBeConcreteStatesOfTheSharedBase() throws Exception {
        final List<Class<?>> states = scanStates();

        assertThat(states).hasSize(2);
        for (final Class<?> state : states) {
            assertThat(Modifier.isPublic(state.getModifiers())).as("%s visibility", state.getSimpleName()).isTrue();
            assertThat(Modifier.isStatic(state.getModifiers())).as("%s nesting", state.getSimpleName()).isTrue();
            assertThat(Modifier.isAbstract(state.getModifiers())).as("%s abstractness", state.getSimpleName()).isFalse();
            assertThat(state.getDeclaredConstructor().newInstance())
                    .as("%s assembly", state.getSimpleName())
                    .isInstanceOf(CalculateChangesBenchmark.DiffScanState.class);
        }
    }

    @Test
    @DisplayName("每个扫描状态应只声明一个 public int 的 @Param 字段，取值等于冻结档位")
    void scanStates_shouldDeclareExactlyOneParamFieldPerDimension() {
        assertThat(scanStates().stream().map(CalculateChangesBenchmarkUnitTest::onlyParamField).map(Field::getName))
                .containsExactlyInAnyOrder("changedFieldCount", "collectionShape");

        final Field changeRatioLevel = onlyParamField(CalculateChangesBenchmark.ChangedFieldCountScan.class);
        assertThat(levelsOf(changeRatioLevel)).containsExactly(
                String.valueOf(CalculateChangesBenchmark.ChangedFieldCountScan.NO_CHANGE_LEVEL),
                String.valueOf(CalculateChangesBenchmark.ChangedFieldCountScan.SINGLE_FIELD_LEVEL),
                String.valueOf(SampleShape.DEFAULT_FIELD_COUNT));
        assertThat(CalculateChangesBenchmark.ChangedFieldCountScan.ALL_FIELDS_LEVEL)
                .as("full change level against the wide sample field count")
                .isEqualTo(SampleShape.DEFAULT_FIELD_COUNT);

        final Field collectionShapeLevel = onlyParamField(CalculateChangesBenchmark.CollectionShapeScan.class);
        assertThat(levelsOf(collectionShapeLevel)).containsExactly(
                String.valueOf(CalculateChangesBenchmark.CollectionShapeScan.VALUE_REPLACEMENT_LEVEL),
                String.valueOf(CalculateChangesBenchmark.CollectionShapeScan.ADDITION_AND_REMOVAL_LEVEL),
                String.valueOf(CalculateChangesBenchmark.CollectionShapeScan.REORDER_LEVEL));

        for (final Class<?> state : scanStates()) {
            final Field parameter = onlyParamField(state);

            assertThat(parameter.getType()).isEqualTo(int.class);
            assertThat(Modifier.isPublic(parameter.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(parameter.getModifiers())).isFalse();
            assertThat(parameter.getAnnotation(Param.class).value()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("每个基准方法应对应一个扫描状态、消费 Blackhole，并按 <操作>By<维度> 命名")
    void benchmarkMethods_shouldPairOneScanStateEachAndConsumeTheResult() {
        final List<Method> benchmarks = benchmarkMethods();

        assertThat(benchmarks).hasSize(2);
        assertThat(benchmarks).allSatisfy(method -> {
            assertThat(Modifier.isPublic(method.getModifiers())).as("%s visibility", method.getName()).isTrue();
            assertThat(method.getReturnType()).as("%s return type", method.getName()).isEqualTo(void.class);
            assertThat(method.getParameterTypes()).hasSize(2);
            assertThat(method.getParameterTypes()[1]).isEqualTo(Blackhole.class);
            assertThat(scanStates()).contains(method.getParameterTypes()[0]);
        });
        for (final Class<?> state : scanStates()) {
            final List<Method> owned = benchmarks.stream()
                    .filter(method -> method.getParameterTypes()[0] == state)
                    .toList();

            assertThat(owned).hasSize(1);
            assertThat(owned.getFirst().getName())
                    .isEqualTo("calculateChangesBy" + dimensionTokenOf(onlyParamField(state).getName()));
        }
    }

    @Test
    @DisplayName("装配钩子应只声明逐迭代装配，不声明逐调用复位")
    void setupHooks_shouldAssemblePerIterationWithoutPerInvocationReset() throws Exception {
        assertThat(Modifier.isAbstract(CalculateChangesBenchmark.DiffScanState.class.getModifiers())).isTrue();

        final Setup iterationHook = CalculateChangesBenchmark.DiffScanState.class
                .getDeclaredMethod("setUpIteration").getAnnotation(Setup.class);
        assertThat(iterationHook).isNotNull();
        assertThat(iterationHook.value()).isEqualTo(Level.Iteration);

        for (final Class<?> state : scanStates()) {
            assertThat(CalculateChangesBenchmark.DiffScanState.class.isAssignableFrom(state))
                    .as("assembly hook coverage of %s", state.getSimpleName())
                    .isTrue();
            for (final Method method : state.getDeclaredMethods()) {
                final Setup hook = method.getAnnotation(Setup.class);

                assertThat(hook == null || hook.value() != Level.Invocation)
                        .as("per invocation reset %s", method.getName())
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("测量体应保持前置状态：每个档位调用后变更条数与 fixture 实例不变")
    void measuredMethods_shouldKeepThePreconditionOfTheirScanState() throws Exception {
        for (final Method method : benchmarkMethods()) {
            final Class<?> stateType = method.getParameterTypes()[0];
            final Field level = onlyParamField(stateType);
            for (final String levelValue : levelsOf(level)) {
                final CalculateChangesBenchmark.DiffScanState scan =
                        (CalculateChangesBenchmark.DiffScanState) stateType.getDeclaredConstructor().newInstance();
                level.set(scan, Integer.parseInt(levelValue));
                scan.setUpIteration();
                final Object sample = scan.sample();
                final ChangeTracker tracker = scan.tracker();
                final int changeCount = tracker.calculateChanges().getLeafChanges().size();

                method.invoke(new CalculateChangesBenchmark(), scan, new Blackhole(BLACKHOLE_CHALLENGE));

                assertThat(tracker.calculateChanges().getLeafChanges())
                        .as("change count after %s level %s", method.getName(), levelValue)
                        .hasSize(changeCount);
                assertThat(scan.sample()).as("sample of %s level %s", method.getName(), levelValue).isSameAs(sample);
                assertThat(scan.tracker()).as("tracker of %s level %s", method.getName(), levelValue).isSameAs(tracker);
            }
        }
    }

    /**
     * Returns the concrete scan state classes declared by the benchmark class, ordered by name.
     *
     * @return the two scan states of this path
     */
    private static List<Class<?>> scanStates() {
        final List<Class<?>> states = new ArrayList<>();
        for (final Class<?> candidate : CalculateChangesBenchmark.class.getDeclaredClasses()) {
            if (DimensionScanState.class.isAssignableFrom(candidate) && !Modifier.isAbstract(candidate.getModifiers())) {
                states.add(candidate);
            }
        }
        states.sort(Comparator.comparing(Class::getSimpleName));
        return states;
    }

    /**
     * Returns the declared benchmark methods of the benchmark class, ordered by name.
     *
     * @return the measured methods
     */
    private static List<Method> benchmarkMethods() {
        return Arrays.stream(CalculateChangesBenchmark.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Benchmark.class))
                .sorted(Comparator.comparing(Method::getName))
                .toList();
    }

    /**
     * Returns the single {@code @Param} field of a scan state.
     *
     * @param state the scan state class
     * @return the scanned dimension field
     */
    private static Field onlyParamField(final Class<?> state) {
        final List<Field> parameters = Arrays.stream(state.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(Param.class))
                .toList();

        assertThat(parameters).as("@Param field count of %s", state.getSimpleName()).hasSize(1);
        return parameters.getFirst();
    }

    /**
     * Turns a scanned dimension field name into the dimension token used by the method name.
     *
     * @param dimension the scanned dimension field name
     * @return the dimension token with an upper case first letter
     */
    private static String dimensionTokenOf(final String dimension) {
        return Character.toUpperCase(dimension.charAt(0)) + dimension.substring(1);
    }

    /**
     * Returns the declared levels of a {@code @Param} field.
     *
     * @param field the scanned dimension field
     * @return the declared levels in declaration order
     */
    private static List<String> levelsOf(final Field field) {
        return List.of(field.getAnnotation(Param.class).value());
    }
}
