package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.sample.SampleMutator;
import com.nona.changeTracking.bench.sample.SampleShape;
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
 * Unit tests for the frozen parameterization pattern of {@link SnapshotBuildBenchmark}: the class
 * level annotation combination, the one dimension per scan state declaration, the one scan state per
 * benchmark method pairing, the naming rule and the consumption of the measured value.
 * <p>
 * The scan state classes are the only place the pattern deviates from the module smoke benchmark,
 * which measures without an explicit dimension: the thread scope and the assembly hooks live on the
 * shared {@link DimensionScanState} and its path specific subclass.
 */
@DisplayName("SnapshotBuildBenchmark 参数化模式单元测试")
class SnapshotBuildBenchmarkUnitTest {

    /**
     * Challenge string JMH requires for a directly instantiated {@link Blackhole}; the measured
     * methods are called outside the JMH harness here, so the test provides their consumer.
     */
    private static final String BLACKHOLE_CHALLENGE =
            "Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.";

    @Test
    @DisplayName("类级注解应与冻结的基准约定一致")
    void classAnnotations_shouldMatchTheFrozenConventions() {
        final BenchmarkMode mode = SnapshotBuildBenchmark.class.getAnnotation(BenchmarkMode.class);
        final Warmup warmup = SnapshotBuildBenchmark.class.getAnnotation(Warmup.class);
        final Measurement measurement = SnapshotBuildBenchmark.class.getAnnotation(Measurement.class);

        assertThat(mode.value()).containsExactly(Mode.AverageTime);
        assertThat(SnapshotBuildBenchmark.class.getAnnotation(OutputTimeUnit.class).value())
                .isEqualTo(TimeUnit.MICROSECONDS);
        assertThat(warmup.iterations()).isEqualTo(3);
        assertThat(warmup.time()).isEqualTo(1);
        assertThat(measurement.iterations()).isEqualTo(5);
        assertThat(measurement.time()).isEqualTo(1);
        assertThat(SnapshotBuildBenchmark.class.getAnnotation(Fork.class).value()).isEqualTo(1);
        assertThat(SnapshotBuildBenchmark.class.getAnnotation(State.class).value()).isEqualTo(Scope.Thread);
    }

    @Test
    @DisplayName("共享扫描状态应为线程作用域的抽象状态，三个具体扫描状态应可直接装配")
    void scanStates_shouldBeConcreteInstantiableStatesOfTheSharedBase() throws Exception {
        assertThat(DimensionScanState.class.getAnnotation(State.class).value()).isEqualTo(Scope.Thread);
        assertThat(Modifier.isAbstract(DimensionScanState.class.getModifiers())).isTrue();

        final List<Class<?>> states = scanStates();

        assertThat(states).hasSize(3);
        for (final Class<?> state : states) {
            assertThat(Modifier.isPublic(state.getModifiers())).as("%s visibility", state.getSimpleName()).isTrue();
            assertThat(Modifier.isStatic(state.getModifiers())).as("%s nesting", state.getSimpleName()).isTrue();
            assertThat(Modifier.isAbstract(state.getModifiers())).as("%s abstractness", state.getSimpleName()).isFalse();
            assertThat(state.getDeclaredConstructor().newInstance()).isInstanceOf(DimensionScanState.class);
        }
    }

    @Test
    @DisplayName("每个扫描状态应只声明一个 public int 的 @Param 字段，取值等于冻结档位")
    void scanStates_shouldDeclareExactlyOneParamFieldPerDimension() {
        final List<String> dimensions = scanStates().stream().map(SnapshotBuildBenchmarkUnitTest::onlyParamField)
                .map(Field::getName)
                .toList();

        assertThat(dimensions).containsExactlyInAnyOrder("fieldCount", "nestingDepth", "collectionSize");
        for (final Class<?> state : scanStates()) {
            final Field parameter = onlyParamField(state);
            final List<String> levels = frozenLevelsOf(parameter.getName());

            assertThat(levels).as("frozen levels of %s", parameter.getName()).isNotEmpty();
            assertThat(parameter.getType()).isEqualTo(int.class);
            assertThat(Modifier.isPublic(parameter.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(parameter.getModifiers())).isFalse();
            assertThat(Modifier.isStatic(parameter.getModifiers())).isFalse();
            assertThat(parameter.getAnnotation(Param.class).value()).containsExactlyElementsOf(levels);
        }
    }

    @Test
    @DisplayName("每个基准方法应对应一个扫描状态、消费 Blackhole，并按 <操作>By<维度> 命名")
    void benchmarkMethods_shouldPairOneScanStateEachAndConsumeTheResult() {
        final List<Method> benchmarks = benchmarkMethods();

        assertThat(benchmarks).hasSize(3);
        assertThat(benchmarks).allSatisfy(method -> {
            assertThat(Modifier.isPublic(method.getModifiers())).as("%s visibility", method.getName()).isTrue();
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
                    .isEqualTo("trackBy" + dimensionTokenOf(onlyParamField(state).getName()));
        }
    }

    @Test
    @DisplayName("三个测量体都应真实执行 track，使样本进入追踪态")
    void measuredMethods_shouldTrackTheSampleOfTheirScanState() throws Exception {
        for (final Method method : benchmarkMethods()) {
            invokeMeasuredMethod(method);
        }
    }

    @Test
    @DisplayName("装配钩子应为逐迭代建前置状态、逐调用重置前置状态")
    void setupHooks_shouldRunPerIterationAndPerInvocation() throws Exception {
        final Setup iterationHook = DimensionScanState.class
                .getDeclaredMethod("setUpIteration").getAnnotation(Setup.class);
        final Setup invocationHook = SnapshotBuildBenchmark.TrackScanState.class
                .getDeclaredMethod("resetPrecondition").getAnnotation(Setup.class);

        assertThat(iterationHook).isNotNull();
        assertThat(invocationHook).isNotNull();
        assertThat(iterationHook.value()).isEqualTo(Level.Iteration);
        assertThat(invocationHook.value()).isEqualTo(Level.Invocation);
        assertThat(Modifier.isAbstract(SnapshotBuildBenchmark.TrackScanState.class.getModifiers())).isTrue();
        for (final Class<?> state : scanStates()) {
            assertThat(SnapshotBuildBenchmark.TrackScanState.class.isAssignableFrom(state))
                    .as("reset hook coverage of %s", state.getSimpleName())
                    .isTrue();
        }
    }

    /**
     * Runs the measured method of a scan state outside the JMH harness.
     *
     * @param method the measured method
     * @return nothing, the method is called for its effect on the scan state
     * @throws Exception if the scan state cannot be assembled or the body fails
     */
    private static void invokeMeasuredMethod(final Method method) throws Exception {
        final Class<?> stateType = method.getParameterTypes()[0];
        final SnapshotBuildBenchmark.TrackScanState scan =
                (SnapshotBuildBenchmark.TrackScanState) stateType.getDeclaredConstructor().newInstance();
        final Field level = onlyParamField(stateType);
        level.set(scan, Integer.parseInt(levelsOf(level).getFirst()));
        scan.setUpIteration();
        scan.resetPrecondition();

        method.invoke(new SnapshotBuildBenchmark(), scan, new Blackhole(BLACKHOLE_CHALLENGE));

        SampleMutator.changeField(scan.sample(), "status");
        assertThat(scan.tracker().calculateChangesFor(scan.sample()).getLeafChanges())
                .as("sample tracked by %s", method.getName())
                .isNotEmpty();
    }

    /**
     * Returns the frozen scan levels annotated on a {@code @Param} field.
     *
     * @param field the scanned dimension field
     * @return the declared levels in declaration order
     */
    private static List<String> levelsOf(final Field field) {
        return List.of(field.getAnnotation(Param.class).value());
    }

    /**
     * Returns the concrete scan state classes declared by the benchmark class, ordered by name.
     *
     * @return the three dimension scan states
     */
    private static List<Class<?>> scanStates() {
        final List<Class<?>> states = new ArrayList<>();
        for (final Class<?> candidate : SnapshotBuildBenchmark.class.getDeclaredClasses()) {
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
        return Arrays.stream(SnapshotBuildBenchmark.class.getDeclaredMethods())
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
     * Returns the frozen scan levels of a dimension.
     *
     * @param dimension the scanned dimension field name
     * @return the frozen levels in declaration order, empty for an unknown dimension
     */
    private static List<String> frozenLevelsOf(final String dimension) {
        return switch (dimension) {
            case "fieldCount" -> List.of(
                    String.valueOf(SampleShape.SUPPORTED_FIELD_COUNT_LOW),
                    String.valueOf(SampleShape.SUPPORTED_FIELD_COUNT_HIGH));
            case "nestingDepth" -> FrozenScanLevels.NESTING_DEPTH.stream().map(String::valueOf).toList();
            case "collectionSize" -> FrozenScanLevels.COLLECTION_SIZE.stream().map(String::valueOf).toList();
            default -> List.of();
        };
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
}