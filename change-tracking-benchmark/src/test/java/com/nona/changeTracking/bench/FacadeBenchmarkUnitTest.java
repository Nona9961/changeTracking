package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.FacadeBenchmark.DirectPath;
import com.nona.changeTracking.bench.FacadeBenchmark.DirectPathState;
import com.nona.changeTracking.bench.FacadeBenchmark.FacadePath;
import com.nona.changeTracking.bench.FacadeBenchmark.FacadePathState;
import com.nona.changeTracking.bench.FacadeBenchmark.PathState;
import com.nona.changeTracking.bench.sample.SampleFamily;
import com.nona.changeTracking.bench.sample.SampleMutator;
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
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the frozen class organization of {@link FacadeBenchmark}: the class level
 * annotation combination, the facade and direct benchmark methods that form one pair per measured
 * action, the parameter shape shared by both sides of a pair, the assembly hooks shared by both path
 * states, and the measured bodies themselves.
 * <p>
 * The facade benchmark deviates from the scanned snapshot benchmark in exactly two documented ways:
 * it carries no dimension scan, because the coverage matrix assigns it a paired comparison instead,
 * and it declares two path states, because both sides of a pair must be assembled through their own
 * path. Both deviations are pinned here, so the pairing cannot silently degrade into two benchmarks
 * that measure the same path.
 */
@DisplayName("FacadeBenchmark 成对对照组织单元测试")
class FacadeBenchmarkUnitTest {

    /**
     * Challenge string JMH requires for a directly instantiated {@link Blackhole}; the measured
     * methods are called outside the JMH harness here, so the test provides their consumer.
     */
    private static final String BLACKHOLE_CHALLENGE =
            "Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.";

    @Test
    @DisplayName("类级注解应与冻结的基准约定一致")
    void classAnnotations_shouldMatchTheFrozenConventions() {
        assertThat(FacadeBenchmark.class.getAnnotation(BenchmarkMode.class).value())
                .containsExactly(Mode.AverageTime);
        assertThat(FacadeBenchmark.class.getAnnotation(OutputTimeUnit.class).value())
                .isEqualTo(TimeUnit.MICROSECONDS);

        final Warmup warmup = FacadeBenchmark.class.getAnnotation(Warmup.class);
        final Measurement measurement = FacadeBenchmark.class.getAnnotation(Measurement.class);

        assertThat(warmup.iterations()).isEqualTo(3);
        assertThat(warmup.time()).isEqualTo(1);
        assertThat(measurement.iterations()).isEqualTo(5);
        assertThat(measurement.time()).isEqualTo(1);
        assertThat(FacadeBenchmark.class.getAnnotation(Fork.class).value()).isEqualTo(1);
        assertThat(FacadeBenchmark.class.getAnnotation(State.class).value()).isEqualTo(Scope.Thread);
    }

    @Test
    @DisplayName("基准方法应构成门面/直连成对，且每对两侧的动作词完全一致")
    void benchmarkMethods_shouldFormFacadeDirectPairsWithMatchingActionTokens() {
        assertThat(benchmarkMethodNames())
                .containsExactly("directBuild", "directTrack", "facadeBuild", "facadeTrack");
        assertThat(actionTokensOf("facade")).isNotEmpty().isEqualTo(actionTokensOf("direct"));
    }

    @Test
    @DisplayName("成对方法的输入形态应一致：装配对只消费 Blackhole，操作对消费各自状态类与 Blackhole")
    void pairedMethods_shouldTakeTheSameParameterShapePerAction() {
        assertThat(parameterTypesOf("facadeBuild")).containsExactly(Blackhole.class);
        assertThat(parameterTypesOf("directBuild")).containsExactly(Blackhole.class);
        assertThat(parameterTypesOf("facadeTrack")).containsExactly(FacadePathState.class, Blackhole.class);
        assertThat(parameterTypesOf("directTrack")).containsExactly(DirectPathState.class, Blackhole.class);

        assertThat(FacadePathState.class.getSuperclass()).isEqualTo(PathState.class);
        assertThat(DirectPathState.class.getSuperclass()).isEqualTo(PathState.class);
        assertThat(Modifier.isAbstract(PathState.class.getModifiers())).isTrue();
    }

    @Test
    @DisplayName("两个状态应共享装配钩子，仅由各自的装配方法承载路径差异")
    void pathStates_shouldShareTheirAssemblyHooksAndCarryTheirOwnAssemblyPath() throws Exception {
        assertThat(PathState.class.getAnnotation(State.class).value()).isEqualTo(Scope.Thread);
        assertThat(PathState.class.getDeclaredMethod("setUpIteration").getAnnotation(Setup.class).value())
                .isEqualTo(Level.Iteration);
        assertThat(PathState.class.getDeclaredMethod("resetPrecondition").getAnnotation(Setup.class).value())
                .isEqualTo(Level.Invocation);

        for (final Class<? extends PathState> state : List.of(FacadePathState.class, DirectPathState.class)) {
            assertThat(Modifier.isPublic(state.getModifiers())).as("%s visibility", state.getSimpleName()).isTrue();
            assertThat(Modifier.isStatic(state.getModifiers())).as("%s nesting", state.getSimpleName()).isTrue();
            assertThat(Modifier.isAbstract(state.getModifiers())).as("%s abstractness", state.getSimpleName()).isFalse();
            assertThat(state.getDeclaredMethod("assembleTracker")).as("assembly path of %s", state.getSimpleName())
                    .isNotNull();
            assertThat(declaredMethodNamesOf(state))
                    .as("hooks overridden by %s", state.getSimpleName())
                    .doesNotContain("setUpIteration", "resetPrecondition");
            assertThat(state.getDeclaredConstructor().newInstance()).isInstanceOf(PathState.class);
        }
    }

    @Test
    @DisplayName("直连路径偏好的提供者名应与 core SPI 契约的默认名一致")
    void defaultProviderName_shouldMatchTheCoreSpiContract() {
        assertThat(DirectPath.DEFAULT_PROVIDER_NAME).isEqualTo("default-reflection");
    }

    @Test
    @DisplayName("直连路径应暴露 SPI 发现与可注入装配入口，门面路径应只暴露门面装配入口")
    void assemblyEntryPoints_shouldExposeTheDiscoverySurfaceOfEachPath() throws Exception {
        assertThat(returnTypeOf(FacadePath.class, "assemble")).isEqualTo(ChangeTracker.class);
        assertThat(returnTypeOf(DirectPath.class, "assemble")).isEqualTo(ChangeTracker.class);
        assertThat(DirectPath.class.getDeclaredMethod("assemble").getParameterCount()).isZero();
        assertThat(returnTypeOf(DirectPath.class, "discoverProviders")).isEqualTo(Map.class);
        assertThat(DirectPath.class.getDeclaredMethod("assemble", Map.class).getParameterTypes())
                .containsExactly(Map.class);
    }

    @Test
    @DisplayName("门面装配入口应产出可用追踪器：登记基线后可检出样本字段变更")
    void facadePath_shouldAssembleATrackerThroughTheFacade() {
        final Object sample = SampleFamily.create(SampleShape.defaults());

        final ChangeTracker tracker = FacadePath.assemble();

        assertThat(tracker).isNotNull();
        assertThat(FacadePath.assemble()).as("a tracker of its own per assembly").isNotSameAs(tracker);
        tracker.track(sample);
        SampleMutator.changeField(sample, "status");
        assertThat(tracker.calculateChangesFor(sample).getLeafChanges()).isNotEmpty();
    }

    @Test
    @DisplayName("直连装配入口应产出可用追踪器：登记基线后可检出样本字段变更")
    void directPath_shouldAssembleATrackerThroughTheSpi() {
        final Object sample = SampleFamily.create(SampleShape.defaults());

        final ChangeTracker tracker = DirectPath.assemble();

        assertThat(tracker).isNotNull();
        assertThat(DirectPath.assemble()).as("a tracker of its own per assembly").isNotSameAs(tracker);
        tracker.track(sample);
        SampleMutator.changeField(sample, "status");
        assertThat(tracker.calculateChangesFor(sample).getLeafChanges()).isNotEmpty();
    }

    @Test
    @DisplayName("门面装配测量体应完成一次装配并被 Blackhole 消费，且不改动门面装配入口")
    void facadeBuild_shouldAssembleAndConsumeItsResult() {
        final Blackhole blackhole = new Blackhole(BLACKHOLE_CHALLENGE);

        new FacadeBenchmark().facadeBuild(blackhole);

        assertThat(FacadePath.assemble()).as("facade assembly after a measured build").isNotNull();
    }

    @Test
    @DisplayName("直连装配测量体应完成一次装配并被 Blackhole 消费，且不改动直连装配入口")
    void directBuild_shouldAssembleAndConsumeItsResult() {
        final Blackhole blackhole = new Blackhole(BLACKHOLE_CHALLENGE);

        new FacadeBenchmark().directBuild(blackhole);

        assertThat(DirectPath.assemble()).as("direct assembly after a measured build").isNotNull();
    }

    @Test
    @DisplayName("门面操作测量体应在逐调用复位后的状态上真实 track 所属样本")
    void facadeTrack_shouldTrackTheSampleOfItsState() {
        final FacadePathState state = new FacadePathState();
        state.setUpIteration();
        state.resetPrecondition();

        new FacadeBenchmark().facadeTrack(state, new Blackhole(BLACKHOLE_CHALLENGE));

        SampleMutator.changeField(state.sample(), "status");
        assertThat(state.tracker().calculateChangesFor(state.sample()).getLeafChanges()).isNotEmpty();
    }

    @Test
    @DisplayName("直连操作测量体应在逐调用复位后的状态上真实 track 所属样本")
    void directTrack_shouldTrackTheSampleOfItsState() {
        final DirectPathState state = new DirectPathState();
        state.setUpIteration();
        state.resetPrecondition();

        new FacadeBenchmark().directTrack(state, new Blackhole(BLACKHOLE_CHALLENGE));

        SampleMutator.changeField(state.sample(), "status");
        assertThat(state.tracker().calculateChangesFor(state.sample()).getLeafChanges()).isNotEmpty();
    }

    /**
     * Returns the declared benchmark methods of the benchmark class, ordered by name.
     *
     * @return the measured methods
     */
    private static List<Method> benchmarkMethods() {
        return Arrays.stream(FacadeBenchmark.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Benchmark.class))
                .sorted(Comparator.comparing(Method::getName))
                .toList();
    }

    /**
     * Returns the names of the declared benchmark methods, ordered by name.
     *
     * @return the measured method names
     */
    private static List<String> benchmarkMethodNames() {
        return benchmarkMethods().stream().map(Method::getName).toList();
    }

    /**
     * Returns the action tokens of one side of the pairs, that is every measured method name with the
     * side prefix removed.
     *
     * @param side the side prefix, either the facade or the direct path
     * @return the action tokens declared on that side
     */
    private static Set<String> actionTokensOf(final String side) {
        return benchmarkMethodNames().stream()
                .filter(name -> name.startsWith(side))
                .map(name -> name.substring(side.length()))
                .collect(Collectors.toSet());
    }

    /**
     * Returns the parameter types of a declared method of the benchmark class.
     *
     * @param methodName the method name
     * @return the parameter types in declaration order
     */
    private static List<Class<?>> parameterTypesOf(final String methodName) {
        return List.of(declaredMethodNamed(methodName).getParameterTypes());
    }

    /**
     * Returns a declared method of the benchmark class by name.
     *
     * @param methodName the method name
     * @return the declared method
     */
    private static Method declaredMethodNamed(final String methodName) {
        return Arrays.stream(FacadeBenchmark.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No benchmark method named " + methodName));
    }

    /**
     * Returns the return type of a declared method of a type.
     *
     * @param type       the declaring type
     * @param methodName the method name
     * @return the return type
     */
    private static Class<?> returnTypeOf(final Class<?> type, final String methodName) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No method named " + methodName
                        + " on " + type.getSimpleName()))
                .getReturnType();
    }

    /**
     * Returns the names of the methods declared by a type itself.
     *
     * @param type the type to inspect
     * @return the declared method names
     */
    private static Set<String> declaredMethodNamesOf(final Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods()).map(Method::getName).collect(Collectors.toSet());
    }
}
