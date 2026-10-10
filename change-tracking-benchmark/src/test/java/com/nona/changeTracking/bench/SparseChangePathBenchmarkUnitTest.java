package com.nona.changeTracking.bench;

import com.nona.changeTracking.tracking.ChangeTracker;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SparseChangePathBenchmark}: the frozen annotation combination, the two single
 * dimension scan states, the one scan state per benchmark method pairing, the iteration assembly hook
 * without a per invocation reset, and the measured bodies keeping the precondition assembled by the
 * scan state.
 */
@DisplayName("SparseChangePathBenchmark 参数化模式单元测试")
class SparseChangePathBenchmarkUnitTest {

    /** Challenge string JMH requires for a directly instantiated {@link Blackhole}. */
    private static final String BLACKHOLE_CHALLENGE =
            "Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.";

    @Test
    @DisplayName("类级注解应与冻结的基准约定一致")
    void classAnnotations_shouldMatchTheFrozenConventions() {
        assertThat(SparseChangePathBenchmark.class.getAnnotation(BenchmarkMode.class).value())
                .containsExactly(Mode.AverageTime);
        assertThat(SparseChangePathBenchmark.class.getAnnotation(OutputTimeUnit.class).value())
                .isEqualTo(TimeUnit.MICROSECONDS);
        final Warmup warmup = SparseChangePathBenchmark.class.getAnnotation(Warmup.class);
        assertThat(warmup.iterations()).isEqualTo(3);
        assertThat(warmup.time()).isEqualTo(1);
        final Measurement measurement = SparseChangePathBenchmark.class.getAnnotation(Measurement.class);
        assertThat(measurement.iterations()).isEqualTo(5);
        assertThat(measurement.time()).isEqualTo(1);
        assertThat(SparseChangePathBenchmark.class.getAnnotation(Fork.class).value()).isEqualTo(1);
        assertThat(SparseChangePathBenchmark.class.getAnnotation(State.class).value()).isEqualTo(Scope.Thread);
    }

    @Test
    @DisplayName("两个扫描状态应为共享稀疏扫描基类的具体状态，可直接装配")
    void scanStates_shouldBeConcreteStatesOfTheSharedSparseBase() throws Exception {
        final List<Class<?>> states = scanStates();

        assertThat(states).hasSize(2);
        assertThat(Modifier.isAbstract(SparseChangePathBenchmark.SparseScanState.class.getModifiers())).isTrue();
        for (final Class<?> state : states) {
            assertThat(Modifier.isPublic(state.getModifiers())).as("%s visibility", state.getSimpleName()).isTrue();
            assertThat(Modifier.isStatic(state.getModifiers())).as("%s nesting", state.getSimpleName()).isTrue();
            assertThat(Modifier.isAbstract(state.getModifiers())).as("%s abstractness", state.getSimpleName()).isFalse();
            assertThat(state.getDeclaredConstructor().newInstance())
                    .as("%s assembly", state.getSimpleName())
                    .isInstanceOf(SparseChangePathBenchmark.SparseScanState.class);
        }
    }

    @Test
    @DisplayName("每个扫描状态应只声明一个 public int 的 @Param 字段，取值等于冻结档位")
    void scanStates_shouldDeclareExactlyOneParamFieldPerState() {
        assertThat(scanStates().stream().map(SparseChangePathBenchmarkUnitTest::onlyParamField).map(Field::getName))
                .containsExactlyInAnyOrder("sparseChangeLevel", "deepChainChangeLevel");

        for (final Class<?> state : scanStates()) {
            final Field parameter = onlyParamField(state);

            assertThat(parameter.getType()).isEqualTo(int.class);
            assertThat(Modifier.isPublic(parameter.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(parameter.getModifiers())).isFalse();
            assertThat(levelsOf(parameter)).containsExactly("0", "1");
        }
    }

    @Test
    @DisplayName("每个基准方法应对应一个扫描状态、消费 Blackhole，并按 <操作>By<维度> 命名")
    void benchmarkMethods_shouldPairOneScanStateEachAndConsumeTheResult() {
        final List<Method> benchmarks = benchmarkMethods();

        assertThat(benchmarks).hasSize(2);
        assertThat(benchmarks).allSatisfy(method -> {
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
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
        final Setup iterationHook = SparseChangePathBenchmark.SparseScanState.class
                .getDeclaredMethod("setUpIteration").getAnnotation(Setup.class);

        assertThat(iterationHook).isNotNull();
        assertThat(iterationHook.value()).isEqualTo(Level.Iteration);
        for (final Class<?> state : scanStates()) {
            for (final Method method : state.getDeclaredMethods()) {
                final Setup hook = method.getAnnotation(Setup.class);

                assertThat(hook == null || hook.value() != Level.Invocation)
                        .as("per invocation reset %s", method.getName())
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("装配后的扫描状态应就绪：样本非空、追踪器已登记基线，档位对应 0 或 1 条变更")
    void setUpIteration_shouldLeaveAReadyFixture() throws Exception {
        for (final Method method : benchmarkMethods()) {
            final Class<?> stateType = method.getParameterTypes()[0];
            final Field level = onlyParamField(stateType);
            for (final String levelValue : levelsOf(level)) {
                final SparseChangePathBenchmark.SparseScanState scan =
                        (SparseChangePathBenchmark.SparseScanState) stateType.getDeclaredConstructor().newInstance();
                level.set(scan, Integer.parseInt(levelValue));
                scan.setUpIteration();

                assertThat(scan.sample()).as("%s level %s sample", stateType.getSimpleName(), levelValue).isNotNull();
                assertThat(scan.tracker().calculateChanges().getLeafChanges())
                        .as("%s level %s change count", stateType.getSimpleName(), levelValue)
                        .hasSize(Integer.parseInt(levelValue));
            }
        }
    }

    @Test
    @DisplayName("测量体应保持前置状态：调用后变更条数与 fixture 实例不变")
    void measuredMethods_shouldKeepThePreconditionOfTheirScanState() throws Exception {
        for (final Method method : benchmarkMethods()) {
            final Class<?> stateType = method.getParameterTypes()[0];
            final Field level = onlyParamField(stateType);
            for (final String levelValue : levelsOf(level)) {
                final SparseChangePathBenchmark.SparseScanState scan =
                        (SparseChangePathBenchmark.SparseScanState) stateType.getDeclaredConstructor().newInstance();
                level.set(scan, Integer.parseInt(levelValue));
                scan.setUpIteration();
                final Object sample = scan.sample();
                final ChangeTracker tracker = scan.tracker();
                final int changeCount = tracker.calculateChanges().getLeafChanges().size();

                method.invoke(new SparseChangePathBenchmark(), scan, new Blackhole(BLACKHOLE_CHALLENGE));

                assertThat(tracker.calculateChanges().getLeafChanges())
                        .as("change count after %s level %s", method.getName(), levelValue)
                        .hasSize(changeCount);
                assertThat(scan.sample()).isSameAs(sample);
                assertThat(scan.tracker()).isSameAs(tracker);
            }
        }
    }

    @Test
    @DisplayName("空状态应被拒绝")
    void measuredMethods_withNullState_shouldThrowNullPointerException() throws Exception {
        for (final Method method : benchmarkMethods()) {
            final Object[] arguments = {null, new Blackhole(BLACKHOLE_CHALLENGE)};

            assertThatThrownBy(() -> method.invoke(new SparseChangePathBenchmark(), arguments))
                    .as("null state of %s", method.getName())
                    .isInstanceOf(InvocationTargetException.class)
                    .hasCauseInstanceOf(NullPointerException.class);
        }
    }

    /**
     * Returns the concrete scan state classes declared by the benchmark class, ordered by name.
     *
     * @return the two scan states of this path
     */
    private static List<Class<?>> scanStates() {
        final List<Class<?>> states = new ArrayList<>();
        for (final Class<?> candidate : SparseChangePathBenchmark.class.getDeclaredClasses()) {
            if (SparseChangePathBenchmark.SparseScanState.class.isAssignableFrom(candidate)
                    && !Modifier.isAbstract(candidate.getModifiers())) {
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
        return Arrays.stream(SparseChangePathBenchmark.class.getDeclaredMethods())
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
