package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.sample.SampleGraphNode;
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
 * Unit tests for {@link SharedSubgraphBenchmark}: the frozen annotation combination, the four single
 * dimension scan states (plain tree, shared subgraph, cyclic graph, mixed graph), the one scan state
 * per benchmark method pairing, the iteration assembly hook without a per invocation reset, and the
 * measured bodies keeping the precondition assembled by the scan state.
 */
@DisplayName("SharedSubgraphBenchmark 参数化模式单元测试")
class SharedSubgraphBenchmarkUnitTest {

    /** Challenge string JMH requires for a directly instantiated {@link Blackhole}. */
    private static final String BLACKHOLE_CHALLENGE =
            "Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.";

    /** Names of the four scanned change level parameters, one per graph topology. */
    private static final List<String> CHANGE_LEVEL_PARAMETERS = List.of(
            "plainTreeChangeLevel", "sharedSubgraphChangeLevel", "cyclicGraphChangeLevel", "mixedGraphChangeLevel");

    @Test
    @DisplayName("类级注解应与冻结的基准约定一致")
    void classAnnotations_shouldMatchTheFrozenConventions() {
        assertThat(SharedSubgraphBenchmark.class.getAnnotation(BenchmarkMode.class).value())
                .containsExactly(Mode.AverageTime);
        assertThat(SharedSubgraphBenchmark.class.getAnnotation(OutputTimeUnit.class).value())
                .isEqualTo(TimeUnit.MICROSECONDS);
        final Warmup warmup = SharedSubgraphBenchmark.class.getAnnotation(Warmup.class);
        assertThat(warmup.iterations()).isEqualTo(3);
        assertThat(warmup.time()).isEqualTo(1);
        final Measurement measurement = SharedSubgraphBenchmark.class.getAnnotation(Measurement.class);
        assertThat(measurement.iterations()).isEqualTo(5);
        assertThat(measurement.time()).isEqualTo(1);
        assertThat(SharedSubgraphBenchmark.class.getAnnotation(Fork.class).value()).isEqualTo(1);
        assertThat(SharedSubgraphBenchmark.class.getAnnotation(State.class).value()).isEqualTo(Scope.Thread);
    }

    @Test
    @DisplayName("共享扫描基类应为抽象线程作用域状态")
    void graphScanState_shouldBeAnAbstractThreadScopedState() {
        final Class<?> base = SharedSubgraphBenchmark.GraphScanState.class;

        assertThat(Modifier.isAbstract(base.getModifiers())).isTrue();
        assertThat(Modifier.isStatic(base.getModifiers())).isTrue();
        assertThat(base.getAnnotation(State.class).value()).isEqualTo(Scope.Thread);
    }

    @Test
    @DisplayName("四个扫描状态应为共享扫描基类的具体状态，可直接装配")
    void scanStates_shouldBeConcreteStatesOfTheSharedGraphBase() throws Exception {
        final List<Class<?>> states = scanStates();

        assertThat(states).hasSize(4);
        for (final Class<?> state : states) {
            assertThat(Modifier.isPublic(state.getModifiers())).as("%s visibility", state.getSimpleName()).isTrue();
            assertThat(Modifier.isStatic(state.getModifiers())).as("%s nesting", state.getSimpleName()).isTrue();
            assertThat(Modifier.isAbstract(state.getModifiers())).as("%s abstractness", state.getSimpleName()).isFalse();
            assertThat(state.getDeclaredConstructor().newInstance())
                    .as("%s assembly", state.getSimpleName())
                    .isInstanceOf(SharedSubgraphBenchmark.GraphScanState.class);
        }
    }

    @Test
    @DisplayName("每个扫描状态应声明一个 public int 的 @Param 字段，取值等于冻结档位")
    void scanStates_shouldDeclareExactlyOneParamFieldPerState() {
        assertThat(scanStates().stream().map(SharedSubgraphBenchmarkUnitTest::onlyParamField).map(Field::getName))
                .containsExactlyInAnyOrderElementsOf(CHANGE_LEVEL_PARAMETERS);

        for (final Class<?> state : scanStates()) {
            final Field parameter = onlyParamField(state);

            assertThat(parameter.getType()).isEqualTo(int.class);
            assertThat(Modifier.isPublic(parameter.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(parameter.getModifiers())).isFalse();
            assertThat(levelsOf(parameter)).containsExactly("0", "1");
        }
    }

    @Test
    @DisplayName("每个扫描状态应实现拓扑构造与变更应用两个钩子")
    void scanStates_shouldImplementBothGraphHooks() {
        for (final Class<?> state : scanStates()) {
            assertThat(declaredMethod(state, "graph").getReturnType())
                    .as("%s graph return type", state.getSimpleName())
                    .isEqualTo(SampleGraphNode.class);
            assertThat(declaredMethod(state, "applyGraphChange", Object.class).getParameterTypes())
                    .as("%s change hook parameters", state.getSimpleName())
                    .containsExactly(Object.class);
        }
    }

    @Test
    @DisplayName("每个基准方法应对应一个扫描状态、消费 Blackhole，并按 <操作>By<维度> 命名")
    void benchmarkMethods_shouldPairOneScanStateEachAndConsumeTheResult() {
        final List<Method> benchmarks = benchmarkMethods();

        assertThat(benchmarks).hasSize(4);
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
        final Setup iterationHook = SharedSubgraphBenchmark.GraphScanState.class
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
                final SharedSubgraphBenchmark.GraphScanState scan = instantiate(stateType, level, levelValue);
                scan.setUpIteration();

                assertThat(scan.sample()).as("%s level %s sample", stateType.getSimpleName(), levelValue).isNotNull();
                assertThat(scan.graph()).as("%s level %s graph", stateType.getSimpleName(), levelValue).isNotNull();
                // 根节点不登记活动节点对（T02 冻结语义，diffRoot 不登记根对）：环图闭合回到根时
                // 该对重新进入并复报根层 layerValue 变更，因此环图档位 1 经两条可达路径各报一条；
                // 其余三拓扑档位 1 仍为 1 条。
                final int expectedChanges = stateType == SharedSubgraphBenchmark.CyclicGraphScanState.class
                        ? 2 * Integer.parseInt(levelValue)
                        : Integer.parseInt(levelValue);
                assertThat(scan.tracker().calculateChanges().getLeafChanges())
                        .as("%s level %s change count", stateType.getSimpleName(), levelValue)
                        .hasSize(expectedChanges);
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
                final SharedSubgraphBenchmark.GraphScanState scan = instantiate(stateType, level, levelValue);
                scan.setUpIteration();
                final Object sample = scan.sample();
                final ChangeTracker tracker = scan.tracker();
                final int changeCount = tracker.calculateChanges().getLeafChanges().size();

                method.invoke(new SharedSubgraphBenchmark(), scan, new Blackhole(BLACKHOLE_CHALLENGE));

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

            assertThatThrownBy(() -> method.invoke(new SharedSubgraphBenchmark(), arguments))
                    .as("null state of %s", method.getName())
                    .isInstanceOf(InvocationTargetException.class)
                    .hasCauseInstanceOf(NullPointerException.class);
        }
    }

    /**
     * Instantiates a scan state and assigns one of its scanned levels.
     *
     * @param stateType  the scan state class
     * @param levelField the single {@code @Param} field of the state
     * @param levelValue the scanned level value
     * @return the assembled scan state instance
     * @throws Exception if the state cannot be instantiated or its level cannot be assigned
     */
    private static SharedSubgraphBenchmark.GraphScanState instantiate(final Class<?> stateType,
                                                                     final Field levelField,
                                                                     final String levelValue) throws Exception {
        final SharedSubgraphBenchmark.GraphScanState scan =
                (SharedSubgraphBenchmark.GraphScanState) stateType.getDeclaredConstructor().newInstance();
        levelField.set(scan, Integer.parseInt(levelValue));
        return scan;
    }

    /**
     * Returns a method declared by the given state class.
     *
     * @param state  the scan state class
     * @param name   the method name
     * @return the declared method
     */
    private static Method declaredMethod(final Class<?> state, final String name,
                                        final Class<?>... parameterTypes) {
        try {
            return state.getDeclaredMethod(name, parameterTypes);
        } catch (final NoSuchMethodException e) {
            throw new AssertionError("Missing method " + name + " on " + state.getSimpleName(), e);
        }
    }

    /**
     * Returns the concrete scan state classes declared by the benchmark class, ordered by name.
     *
     * @return the four scan states of this path
     */
    private static List<Class<?>> scanStates() {
        final List<Class<?>> states = new ArrayList<>();
        for (final Class<?> candidate : SharedSubgraphBenchmark.class.getDeclaredClasses()) {
            if (SharedSubgraphBenchmark.GraphScanState.class.isAssignableFrom(candidate)
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
        return Arrays.stream(SharedSubgraphBenchmark.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Benchmark.class))
                .sorted(Comparator.comparing(Method::getName))
                .toList();
    }

    /**
     * Returns the single {@code @Param} field of a scan state.
     *
     * @param state the scan state class
     * @return the scanned change level field
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
