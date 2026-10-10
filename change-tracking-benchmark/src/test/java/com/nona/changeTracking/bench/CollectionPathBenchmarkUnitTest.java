package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.sample.SampleOrder;
import com.nona.changeTracking.bench.sample.SampleShape;
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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CollectionPathBenchmark}: the frozen annotation combination, the single
 * dimension state, the one scan state per benchmark method pairing, the iteration assembly hook, and
 * the measured body keeping the precondition assembled by the state.
 */
@DisplayName("CollectionPathBenchmark 参数化模式单元测试")
class CollectionPathBenchmarkUnitTest {

    /** Challenge string JMH requires for a directly instantiated {@link Blackhole}. */
    private static final String BLACKHOLE_CHALLENGE =
            "Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.";

    @Test
    @DisplayName("类级注解应与冻结的基准约定一致")
    void classAnnotations_shouldMatchTheFrozenConventions() {
        assertThat(CollectionPathBenchmark.class.getAnnotation(BenchmarkMode.class).value())
                .containsExactly(Mode.AverageTime);
        assertThat(CollectionPathBenchmark.class.getAnnotation(OutputTimeUnit.class).value())
                .isEqualTo(TimeUnit.MICROSECONDS);
        final Warmup warmup = CollectionPathBenchmark.class.getAnnotation(Warmup.class);
        assertThat(warmup.iterations()).isEqualTo(3);
        assertThat(warmup.time()).isEqualTo(1);
        final Measurement measurement = CollectionPathBenchmark.class.getAnnotation(Measurement.class);
        assertThat(measurement.iterations()).isEqualTo(5);
        assertThat(measurement.time()).isEqualTo(1);
        assertThat(CollectionPathBenchmark.class.getAnnotation(Fork.class).value()).isEqualTo(1);
        assertThat(CollectionPathBenchmark.class.getAnnotation(State.class).value()).isEqualTo(Scope.Thread);
    }

    @Test
    @DisplayName("状态类应为 public static @State，可直接装配并给出默认形状")
    void state_shouldBePublicStaticStateOfTheDefaultShape() throws Exception {
        final Class<?> state = stateType();

        assertThat(Modifier.isPublic(state.getModifiers())).isTrue();
        assertThat(Modifier.isStatic(state.getModifiers())).isTrue();
        assertThat(state.getAnnotation(State.class)).isNotNull();
        final CollectionPathBenchmark.CollectionIdentifierPathState instance =
                (CollectionPathBenchmark.CollectionIdentifierPathState) state.getDeclaredConstructor().newInstance();

        assertThat(instance.shape()).isEqualTo(SampleShape.defaults());
    }

    @Test
    @DisplayName("状态类应只声明一个 public int 的 @Param 字段，取值等于冻结档位")
    void state_shouldDeclareExactlyOneParamField() {
        final Field parameter = onlyParamField(stateType());

        assertThat(parameter.getName()).isEqualTo("collectionChangeShape");
        assertThat(parameter.getType()).isEqualTo(int.class);
        assertThat(Modifier.isPublic(parameter.getModifiers())).isTrue();
        assertThat(Modifier.isStatic(parameter.getModifiers())).isFalse();
        assertThat(levelsOf(parameter)).containsExactly("0", "1", "2", "3");
    }

    @Test
    @DisplayName("基准方法应对应唯一状态、消费 Blackhole，并按 <操作>By<维度> 命名")
    void benchmarkMethod_shouldPairTheStateAndConsumeTheResult() {
        final List<Method> benchmarks = benchmarkMethods();

        assertThat(benchmarks).hasSize(1);
        final Method method = benchmarks.getFirst();
        assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
        assertThat(method.getReturnType()).isEqualTo(void.class);
        assertThat(method.getParameterTypes()).hasSize(2);
        assertThat(method.getParameterTypes()[1]).isEqualTo(Blackhole.class);
        assertThat(method.getParameterTypes()[0]).isSameAs(stateType());
        assertThat(method.getName()).isEqualTo("calculateChangesByCollectionChangeShape");
    }

    @Test
    @DisplayName("状态类应只声明逐迭代装配钩子，不声明逐调用复位")
    void setupHook_shouldAssemblePerIteration() {
        final Setup iterationHook = setupHookOf();

        assertThat(iterationHook).isNotNull();
        assertThat(iterationHook.value()).isEqualTo(Level.Iteration);
        for (final Method method : stateType().getDeclaredMethods()) {
            final Setup hook = method.getAnnotation(Setup.class);

            assertThat(hook == null || method.getName().equals("setUpIteration"))
                    .as("per invocation or unexpected reset %s", method.getName())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("装配后的状态应就绪：默认形状样本已登记基线，档位对应预期变更")
    void setUpIteration_shouldLeaveAReadyFixture() throws Exception {
        for (final String levelValue : levelsOf(onlyParamField(stateType()))) {
            final CollectionPathBenchmark.CollectionIdentifierPathState scan = newState(levelValue);
            scan.setUpIteration();

            final SampleOrder sample = (SampleOrder) scan.sample();
            assertThat(sample.items()).hasSize(SampleShape.DEFAULT_COLLECTION_SIZE);
            assertThat(scan.tracker()).isNotNull();
            final int leafChanges = scan.tracker().calculateChanges().getLeafChanges().size();
            if (Integer.parseInt(levelValue) == 0 || Integer.parseInt(levelValue) == 3) {
                assertThat(leafChanges).as("level %s leaf changes", levelValue).isZero();
            } else {
                assertThat(leafChanges).as("level %s leaf changes", levelValue).isPositive();
            }
        }
    }

    @Test
    @DisplayName("测量体应保持前置状态：调用后变更条数与 fixture 实例不变")
    void measuredMethod_shouldKeepThePreconditionOfTheState() throws Exception {
        for (final String levelValue : levelsOf(onlyParamField(stateType()))) {
            final CollectionPathBenchmark.CollectionIdentifierPathState scan = newState(levelValue);
            scan.setUpIteration();
            final Object sample = scan.sample();
            final ChangeTracker tracker = scan.tracker();
            final int changeCount = tracker.calculateChanges().getLeafChanges().size();

            benchmarkMethods().getFirst().invoke(new CollectionPathBenchmark(), scan, new Blackhole(BLACKHOLE_CHALLENGE));

            assertThat(tracker.calculateChanges().getLeafChanges())
                    .as("change count after level %s", levelValue)
                    .hasSize(changeCount);
            assertThat(scan.sample()).isSameAs(sample);
            assertThat(scan.tracker()).isSameAs(tracker);
        }
    }

    @Test
    @DisplayName("空状态应被拒绝")
    void measuredMethod_withNullState_shouldThrowNullPointerException() throws Exception {
        final Method method = benchmarkMethods().getFirst();
        final Object[] arguments = {null, new Blackhole(BLACKHOLE_CHALLENGE)};

        assertThatThrownBy(() -> method.invoke(new CollectionPathBenchmark(), arguments))
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(NullPointerException.class);
    }

    /**
     * Creates and configures a state instance of the given scanned level.
     *
     * @param levelValue the scanned level as declared by {@code @Param}
     * @return the configured state
     * @throws Exception if the state cannot be instantiated or its level field set
     */
    private static CollectionPathBenchmark.CollectionIdentifierPathState newState(final String levelValue) throws Exception {
        final CollectionPathBenchmark.CollectionIdentifierPathState scan =
                (CollectionPathBenchmark.CollectionIdentifierPathState) stateType().getDeclaredConstructor().newInstance();
        onlyParamField(stateType()).set(scan, Integer.parseInt(levelValue));
        return scan;
    }

    /**
     * Returns the single measured method of the benchmark class.
     *
     * @return the measured methods
     */
    private static List<Method> benchmarkMethods() {
        return Arrays.stream(CollectionPathBenchmark.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Benchmark.class))
                .toList();
    }

    /**
     * Returns the state class declared by the benchmark class.
     *
     * @return the single state class
     */
    private static Class<?> stateType() {
        return CollectionPathBenchmark.CollectionIdentifierPathState.class;
    }

    /**
     * Returns the iteration assembly hook of the state class.
     *
     * @return the iteration setup hook
     */
    private static Setup setupHookOf() {
        try {
            return stateType().getDeclaredMethod("setUpIteration").getAnnotation(Setup.class);
        } catch (final NoSuchMethodException e) {
            return null;
        }
    }

    /**
     * Returns the single {@code @Param} field of the state class.
     *
     * @param state the state class
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
     * Returns the declared levels of a {@code @Param} field.
     *
     * @param field the scanned dimension field
     * @return the declared levels in declaration order
     */
    private static List<String> levelsOf(final Field field) {
        return List.of(field.getAnnotation(Param.class).value());
    }
}
