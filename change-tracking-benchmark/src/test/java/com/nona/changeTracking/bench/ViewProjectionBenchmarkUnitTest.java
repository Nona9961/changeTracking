package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.sample.SampleShape;
import com.nona.changeTracking.change.ChangeSet;
import com.nona.changeTracking.tracking.ChangeTracker;
import org.junit.jupiter.api.BeforeEach;
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
 * {@link ViewProjectionBenchmark} 单元测试：冻结注解组合、深链扫描状态、四个载体与测量方法的配对、
 * 装配钩子不含逐调用复位，以及测量体保持前置状态。
 * <p>
 * 每个用例自建扫描状态（含 {@link ViewProjectionBenchmark.ViewScanState#setUpIteration()}），
 * 不依赖其它用例产物；扫描状态的装配只做样本构造、基线登记与变更集计算，不进入被测算法的优化路径。
 */
@DisplayName("ViewProjectionBenchmark 参数化模式单元测试")
class ViewProjectionBenchmarkUnitTest {

    /** 直接实例化 {@link Blackhole} 所需的挑战串。 */
    private static final String BLACKHOLE_CHALLENGE =
            "Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.";

    /** 四个扫描状态的名字。 */
    private static final List<String> SCAN_STATE_NAMES = List.of(
            "CompleteViewScan", "LeafViewScan", "RepeatedViewScan", "ViewChainScan");

    /** 四个载体的测量方法名。 */
    private static final List<String> BENCHMARK_METHOD_NAMES = List.of(
            "calculateChangesAndProjectByViewChangeLevel",
            "projectAllChangesByViewChangeLevel",
            "projectAllChangesRepeatedlyByViewChangeLevel",
            "projectLeafChangesByViewChangeLevel");

    /** 装配钩子调用的计数器，验证测量体不重复装配。 */
    private static int setUpCalls;

    @BeforeEach
    void setUp() {
        setUpCalls = 0;
    }

    @Test
    @DisplayName("类级注解应与冻结的基准约定一致")
    void classAnnotations_shouldMatchTheFrozenConventions() {
        assertThat(ViewProjectionBenchmark.class.getAnnotation(BenchmarkMode.class).value())
                .containsExactly(Mode.AverageTime);
        assertThat(ViewProjectionBenchmark.class.getAnnotation(OutputTimeUnit.class).value())
                .isEqualTo(TimeUnit.MICROSECONDS);
        final Warmup warmup = ViewProjectionBenchmark.class.getAnnotation(Warmup.class);
        assertThat(warmup.iterations()).isEqualTo(3);
        assertThat(warmup.time()).isEqualTo(1);
        final Measurement measurement = ViewProjectionBenchmark.class.getAnnotation(Measurement.class);
        assertThat(measurement.iterations()).isEqualTo(5);
        assertThat(measurement.time()).isEqualTo(1);
        assertThat(ViewProjectionBenchmark.class.getAnnotation(Fork.class).value()).isEqualTo(1);
        assertThat(ViewProjectionBenchmark.class.getAnnotation(State.class).value()).isEqualTo(Scope.Thread);
    }

    @Test
    @DisplayName("共享扫描基类应继承 DimensionScanState，且四个具体扫描状态可直接装配")
    void scanStates_shouldBeConcreteStatesOfTheSharedBase() throws Exception {
        assertThat(ViewProjectionBenchmark.ViewScanState.class.getSuperclass()).isEqualTo(DimensionScanState.class);
        assertThat(Modifier.isAbstract(ViewProjectionBenchmark.ViewScanState.class.getModifiers())).isTrue();

        final List<Class<?>> states = scanStates();
        assertThat(states).hasSize(4);
        for (final Class<?> state : states) {
            assertThat(Modifier.isStatic(state.getModifiers())).as("%s nesting", state.getSimpleName()).isTrue();
            assertThat(Modifier.isAbstract(state.getModifiers())).as("%s abstractness", state.getSimpleName()).isFalse();
            assertThat(state.getDeclaredConstructor().newInstance())
                    .as("%s assembly", state.getSimpleName())
                    .isInstanceOf(ViewProjectionBenchmark.ViewScanState.class);
        }
    }

    @Test
    @DisplayName("每个扫描状态应声明一个 public int 的 @Param 字段，档位为 0 与 1")
    void scanStates_shouldDeclareExactlyOneParamField() {
        assertThat(scanStates().stream().map(ViewProjectionBenchmarkUnitTest::onlyParamField).map(Field::getName))
                .containsOnly("viewChangeLevel");

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

        assertThat(benchmarks).extracting(Method::getName)
                .containsExactlyInAnyOrderElementsOf(BENCHMARK_METHOD_NAMES);
        assertThat(benchmarks).allSatisfy(method -> {
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
            assertThat(method.getParameterTypes()).hasSize(2);
            assertThat(method.getParameterTypes()[1]).isEqualTo(Blackhole.class);
            assertThat(scanStates()).contains(method.getParameterTypes()[0]);
        });
        assertThat(benchmarks).extracting(method -> method.getParameterTypes()[0].getSimpleName())
                .containsExactlyInAnyOrderElementsOf(SCAN_STATE_NAMES);
    }

    @Test
    @DisplayName("装配钩子应只声明逐迭代装配，不声明逐调用复位")
    void setupHooks_shouldAssemblePerIterationWithoutPerInvocationReset() throws Exception {
        final Setup iterationHook = ViewProjectionBenchmark.ViewScanState.class
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
    @DisplayName("装配后的扫描状态应就绪：样本非空、追踪器已登记基线、变更集可投影，档位对应 0 或 1 条叶子变更")
    void setUpIteration_shouldLeaveAReadyFixture() throws Exception {
        for (final Method method : benchmarkMethods()) {
            final Class<?> stateType = method.getParameterTypes()[0];
            final Field level = onlyParamField(stateType);
            for (final String levelValue : levelsOf(level)) {
                final ViewProjectionBenchmark.ViewScanState scan = instantiate(stateType, level, levelValue);
                scan.setUpIteration();

                assertThat(scan.sample()).as("%s level %s sample", stateType.getSimpleName(), levelValue).isNotNull();
                assertThat(scan.tracker()).as("%s level %s tracker", stateType.getSimpleName(), levelValue).isNotNull();
                assertThat(scan.shape()).isEqualTo(SampleShape.deepChain());
                final ChangeSet changeSet = trackerChangeSet(scan);
                assertThat(changeSet).as("%s level %s change set", stateType.getSimpleName(), levelValue).isNotNull();
                assertThat(changeSet.getLeafChanges())
                        .as("%s level %s leaf count", stateType.getSimpleName(), levelValue)
                        .hasSize(Integer.parseInt(levelValue));
            }
        }
    }

    @Test
    @DisplayName("测量体应保持前置状态：调用后样本、追踪器与变更条数不变，且不触发额外装配")
    void measuredMethods_shouldKeepThePreconditionOfTheirScanState() throws Exception {
        for (final Method method : benchmarkMethods()) {
            final Class<?> stateType = method.getParameterTypes()[0];
            final Field level = onlyParamField(stateType);
            for (final String levelValue : levelsOf(level)) {
                final ViewProjectionBenchmark.ViewScanState scan = instantiate(stateType, level, levelValue);
                scan.setUpIteration();
                final Object sample = scan.sample();
                final ChangeTracker tracker = scan.tracker();
                final int changeCount = trackerCalculateChanges(tracker).getLeafChanges().size();
                final int setUpCallsBeforeMeasuredBody = setUpCalls;

                method.invoke(new ViewProjectionBenchmark(), scan, new Blackhole(BLACKHOLE_CHALLENGE));

                assertThat(setUpCalls).as("assembly calls of %s", method.getName())
                        .isEqualTo(setUpCallsBeforeMeasuredBody);
                assertThat(trackerCalculateChanges(tracker).getLeafChanges())
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

            assertThatThrownBy(() -> method.invoke(new ViewProjectionBenchmark(), arguments))
                    .as("null state of %s", method.getName())
                    .isInstanceOf(InvocationTargetException.class)
                    .hasCauseInstanceOf(NullPointerException.class);
        }
    }

    /**
     * 取扫描状态装配得到的变更集（经包内可见访问器）。
     *
     * @param scan 扫描状态
     * @return 装配阶段计算的变更集
     * @throws Exception 反射失败
     */
    private static ChangeSet trackerChangeSet(final ViewProjectionBenchmark.ViewScanState scan) throws Exception {
        final Method accessor = ViewProjectionBenchmark.ViewScanState.class.getDeclaredMethod("changeSet");
        accessor.setAccessible(true);
        return (ChangeSet) accessor.invoke(scan);
    }

    /**
     * 计算追踪器的变更集（每例自建，不读装配结果）。
     *
     * @param tracker 追踪器
     * @return 变更集
     */
    private static ChangeSet trackerCalculateChanges(final ChangeTracker tracker) {
        return tracker.calculateChanges();
    }

    /**
     * 实例化扫描状态并赋值其扫描档位。
     *
     * @param stateType  扫描状态类型
     * @param levelField 扫描状态的 @Param 字段
     * @param levelValue 扫描档位
     * @return 装配好的扫描状态
     * @throws Exception 反射失败
     */
    private static ViewProjectionBenchmark.ViewScanState instantiate(final Class<?> stateType,
                                                                     final Field levelField,
                                                                     final String levelValue) throws Exception {
        final ViewProjectionBenchmark.ViewScanState scan =
                (ViewProjectionBenchmark.ViewScanState) stateType.getDeclaredConstructor().newInstance();
        levelField.set(scan, Integer.parseInt(levelValue));
        setUpCalls++;
        return scan;
    }

    /**
     * 返回基准类声明的四个具体扫描状态，按名字排序。
     *
     * @return 扫描状态列表
     */
    private static List<Class<?>> scanStates() {
        final List<Class<?>> states = new ArrayList<>();
        for (final Class<?> candidate : ViewProjectionBenchmark.class.getDeclaredClasses()) {
            if (ViewProjectionBenchmark.ViewScanState.class.isAssignableFrom(candidate)
                    && !Modifier.isAbstract(candidate.getModifiers())) {
                states.add(candidate);
            }
        }
        states.sort(Comparator.comparing(Class::getSimpleName));
        return states;
    }

    /**
     * 返回基准类的测量方法，按名字排序。
     *
     * @return 测量方法列表
     */
    private static List<Method> benchmarkMethods() {
        return Arrays.stream(ViewProjectionBenchmark.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Benchmark.class))
                .sorted(Comparator.comparing(Method::getName))
                .toList();
    }

    /**
     * 返回扫描状态的唯一 @Param 字段。
     *
     * @param state 扫描状态类型
     * @return 扫描档位字段
     */
    private static Field onlyParamField(final Class<?> state) {
        final List<Field> parameters = Arrays.stream(state.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(Param.class))
                .toList();

        assertThat(parameters).as("@Param field count of %s", state.getSimpleName()).hasSize(1);
        return parameters.getFirst();
    }

    /**
     * 返回 @Param 字段声明的档位。
     *
     * @param field 扫描档位字段
     * @return 档位列表
     */
    private static List<String> levelsOf(final Field field) {
        return List.of(field.getAnnotation(Param.class).value());
    }
}
