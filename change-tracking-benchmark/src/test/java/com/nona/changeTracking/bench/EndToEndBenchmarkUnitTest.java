package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.sample.SampleLineItem;
import com.nona.changeTracking.bench.sample.SampleOrder;
import com.nona.changeTracking.bench.sample.SampleOrderSummary;
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
 * Unit tests for the frozen parameterization pattern of {@link EndToEndBenchmark}: the class level
 * annotation combination, the one dimension per scan state declaration, the one scan state per
 * benchmark method pairing, the naming rule, the assembly hooks and the whole path the measured
 * methods run.
 * <p>
 * The whole path tests are the behavioral tests of this class: they assemble the scan state, reset the
 * consumed precondition and call the measured method, then assert the path effect on the sample (one
 * appended item) and on the tracker (a change set holding the appended item). The measured methods are
 * invoked by reflection outside the JMH harness, so the asserted effect comes from the measured path
 * itself and not from the benchmark runtime.
 */
@DisplayName("EndToEndBenchmark 参数化模式单元测试")
class EndToEndBenchmarkUnitTest {

    /**
     * Challenge string JMH requires for a directly instantiated {@link Blackhole}; the measured
     * methods are called outside the JMH harness here, so the test provides their consumer.
     */
    private static final String BLACKHOLE_CHALLENGE =
            "Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.";

    @Test
    @DisplayName("类级注解应与冻结的基准约定一致")
    void classAnnotations_shouldMatchTheFrozenConventions() {
        final BenchmarkMode mode = EndToEndBenchmark.class.getAnnotation(BenchmarkMode.class);
        final Warmup warmup = EndToEndBenchmark.class.getAnnotation(Warmup.class);
        final Measurement measurement = EndToEndBenchmark.class.getAnnotation(Measurement.class);

        assertThat(mode.value()).containsExactly(Mode.AverageTime);
        assertThat(EndToEndBenchmark.class.getAnnotation(OutputTimeUnit.class).value())
                .isEqualTo(TimeUnit.MICROSECONDS);
        assertThat(warmup.iterations()).isEqualTo(3);
        assertThat(warmup.time()).isEqualTo(1);
        assertThat(measurement.iterations()).isEqualTo(5);
        assertThat(measurement.time()).isEqualTo(1);
        assertThat(EndToEndBenchmark.class.getAnnotation(Fork.class).value()).isEqualTo(1);
        assertThat(EndToEndBenchmark.class.getAnnotation(State.class).value()).isEqualTo(Scope.Thread);
    }

    @Test
    @DisplayName("路径扫描状态应为抽象类，三个具体扫描状态应可直接装配")
    void scanStates_shouldBeConcreteInstantiableStatesOfThePathBase() throws Exception {
        final Class<EndToEndBenchmark.FullPathScanState> pathBase = EndToEndBenchmark.FullPathScanState.class;

        assertThat(Modifier.isAbstract(pathBase.getModifiers())).isTrue();
        assertThat(DimensionScanState.class.isAssignableFrom(pathBase)).isTrue();

        final List<Class<?>> states = scanStates();

        assertThat(states).hasSize(3);
        for (final Class<?> state : states) {
            assertThat(Modifier.isPublic(state.getModifiers())).as("%s visibility", state.getSimpleName()).isTrue();
            assertThat(Modifier.isStatic(state.getModifiers())).as("%s nesting", state.getSimpleName()).isTrue();
            assertThat(Modifier.isAbstract(state.getModifiers())).as("%s abstractness", state.getSimpleName()).isFalse();
            assertThat(pathBase.isAssignableFrom(state)).as("%s path base", state.getSimpleName()).isTrue();
            assertThat(state.getDeclaredConstructor().newInstance()).isInstanceOf(pathBase);
        }
    }

    @Test
    @DisplayName("每个扫描状态应只声明一个 public int 的 @Param 字段，取值等于冻结档位")
    void scanStates_shouldDeclareExactlyOneParamFieldPerDimension() {
        final List<String> dimensions = scanStates().stream().map(EndToEndBenchmarkUnitTest::onlyParamField)
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
                    .isEqualTo("trackAndDiffBy" + dimensionTokenOf(onlyParamField(state).getName()));
        }
    }

    @Test
    @DisplayName("装配钩子应为逐迭代建前置状态、逐调用重置前置状态")
    void setupHooks_shouldRunPerIterationAndPerInvocation() throws Exception {
        final Setup iterationHook = DimensionScanState.class
                .getDeclaredMethod("setUpIteration").getAnnotation(Setup.class);
        final Setup invocationHook = EndToEndBenchmark.FullPathScanState.class
                .getDeclaredMethod("resetPrecondition").getAnnotation(Setup.class);

        assertThat(iterationHook).isNotNull();
        assertThat(invocationHook).isNotNull();
        assertThat(iterationHook.value()).isEqualTo(Level.Iteration);
        assertThat(invocationHook.value()).isEqualTo(Level.Invocation);
    }

    @Test
    @DisplayName("字段数档位的测量体应真实执行 track→就地追加→calculateChanges 全链路")
    void measuredMethod_shouldRunTheWholePathForTheFieldCountScan() throws Exception {
        runWholePath(EndToEndBenchmark.FieldCountScan.class, SampleShape.SUPPORTED_FIELD_COUNT_LOW);
    }

    @Test
    @DisplayName("嵌套深度档位的测量体应真实执行 track→就地追加→calculateChanges 全链路")
    void measuredMethod_shouldRunTheWholePathForTheNestingDepthScan() throws Exception {
        runWholePath(EndToEndBenchmark.NestingDepthScan.class, 1);
    }

    @Test
    @DisplayName("集合规模档位的测量体应真实执行 track→就地追加→calculateChanges 全链路")
    void measuredMethod_shouldRunTheWholePathForTheCollectionSizeScan() throws Exception {
        runWholePath(EndToEndBenchmark.CollectionSizeScan.class, 10);
    }

    /**
     * Assembles one scan state, resets the precondition the measured call consumes and calls the
     * measured method of that scan state outside the JMH harness.
     *
     * @param stateType the scan state class to assemble
     * @param level     the scanned dimension level to run
     * @throws Exception if the scan state cannot be assembled or the measured body fails
     */
    private static void runWholePath(final Class<?> stateType, final int level) throws Exception {
        final Object state = stateType.getDeclaredConstructor().newInstance();
        onlyParamField(stateType).set(state, level);
        final EndToEndBenchmark.FullPathScanState scan = (EndToEndBenchmark.FullPathScanState) state;
        scan.setUpIteration();
        final int initialItemCount = itemIdsOf(scan.sample()).size();
        scan.resetPrecondition();

        measuredMethodOf(stateType).invoke(new EndToEndBenchmark(), scan, new Blackhole(BLACKHOLE_CHALLENGE));

        assertThat(itemIdsOf(scan.sample()))
                .as("item count after %s of level %d", stateType.getSimpleName(), level)
                .hasSize(initialItemCount + 1);
        assertThat(scan.tracker().calculateChangesFor(scan.sample()).getLeafChanges())
                .as("changes of the measured call of %s of level %d", stateType.getSimpleName(), level)
                .hasSize(1);
    }

    /**
     * Returns the concrete scan state classes declared by the benchmark class, ordered by name.
     *
     * @return the three dimension scan states
     */
    private static List<Class<?>> scanStates() {
        final List<Class<?>> states = new ArrayList<>();
        for (final Class<?> candidate : EndToEndBenchmark.class.getDeclaredClasses()) {
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
        return Arrays.stream(EndToEndBenchmark.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Benchmark.class))
                .sorted(Comparator.comparing(Method::getName))
                .toList();
    }

    /**
     * Returns the declared benchmark method owning the given scan state.
     *
     * @param stateType the scan state class
     * @return the measured method of that scan state
     * @throws IllegalStateException if the scan state owns no measured method
     */
    private static Method measuredMethodOf(final Class<?> stateType) {
        return benchmarkMethods().stream()
                .filter(method -> method.getParameterTypes()[0] == stateType)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No benchmark method for " + stateType.getSimpleName()));
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
            case "nestingDepth" -> List.of("1", "2", "3", "4", "5");
            case "collectionSize" -> List.of("10", "100", "1000");
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

    /**
     * Returns the item collection of a sample root.
     *
     * @param sample a sample root created by the frozen sample family
     * @return the item collection of the sample
     * @throws IllegalStateException if the sample type is not a sample root of this family
     */
    private static List<?> itemsOf(final Object sample) {
        if (sample instanceof SampleOrder order) {
            return order.items();
        }
        if (sample instanceof SampleOrderSummary summary) {
            return summary.items();
        }
        throw new IllegalStateException("Unsupported sample type: " + sample.getClass().getName());
    }

    /**
     * Returns the business identifiers of the item collection, in collection order.
     *
     * @param sample a sample root created by the frozen sample family
     * @return the item identifiers in collection order
     */
    private static List<Long> itemIdsOf(final Object sample) {
        return itemsOf(sample).stream().map(item -> ((SampleLineItem) item).id()).toList();
    }
}
