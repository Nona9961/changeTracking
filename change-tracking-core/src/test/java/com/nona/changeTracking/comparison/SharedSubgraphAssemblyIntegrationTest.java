package com.nona.changeTracking.comparison;

import com.nona.changeTracking.change.Change;
import com.nona.changeTracking.change.ChangeSet;
import com.nona.changeTracking.snapshot.ObjectNode;
import com.nona.changeTracking.snapshot.ValueNode;
import com.nona.changeTracking.tracking.BaselineSnapshot;
import com.nona.changeTracking.tracking.ChangeTracker;
import com.nona.changeTracking.tracking.TrackingCapabilityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 共享子图复用的装配面集成测试：用真实 SPI 装配的默认 provider、真实 capability、
 * 真实默认快照策略构建的快照与真实 {@link ChangeTracker} 链路，验证单元测试用
 * 手工节点树覆盖不到的面——业务对象图的共享引用在真实快照中保留、旧新快照侧为独立且相等的值实例、
 * 复用与基线捕获/恢复的互操作。
 * <p>
 * 计数由测试侧 {@link EqualsCallCounter} 承载：快照构建不比较计数叶子，旧新快照侧使用独立且相等的
 * {@link EqualsCountingValue} 实例，因此 {@code calculateChanges()} 的 {@code equals} 调用次数
 * 就是叶子语义比较次数。默认 provider 经 {@code ServiceLoader} 按服务实现的类名发现，
 * 计数载体与本测试都不编译期引用库内部实现包。
 */
@DisplayName("共享子图复用装配面集成测试")
class SharedSubgraphAssemblyIntegrationTest {

    /**
     * 默认 provider 的服务实现类名（按名称发现，避免测试编译期引用内部实现包）。
     */
    private static final String DEFAULT_PROVIDER_CLASS_NAME =
            "com.nona.changeTracking.tracking.DefaultTrackingCapabilityProvider";

    /**
     * 复现样本的分支层数：每侧 17 个独立对象的共享链。
     */
    private static final int REPRODUCTION_DEPTH = 16;

    /**
     * 复现样本每侧独立对象数（分支层数加末端）。
     */
    private static final long REPRODUCTION_INDEPENDENT_OBJECTS = 17L;

    /**
     * 每例新建的计数，避免跨例状态污染。
     */
    private EqualsCallCounter counter;

    @BeforeEach
    void setUp() {
        counter = new EqualsCallCounter();
    }

    @Nested
    @DisplayName("深度 16 复现样本的真实链路")
    class ReproductionChain {

        @Test
        @DisplayName("真实快照保留共享引用，快照构建不比较计数叶子")
        void realSnapshot_shouldPreserveSharingWithoutComparingValues() {
            final ChangeTracker tracker = tracker();
            final SharedGraphProbe sample = sharedGraph(REPRODUCTION_DEPTH, "");

            tracker.track(sample);

            assertThat(counter.count()).isZero();
            final ValueNode baselineRoot = tracker.captureBaseline().entities().get(sample);
            assertThat(baselineRoot).isInstanceOf(ObjectNode.class);
            final ObjectNode root = (ObjectNode) baselineRoot;
            assertThat(root.field("first")).isSameAs(root.field("second"));
        }

        @Test
        @DisplayName("独立且相等的旧新值实例下，复用后每个独立对象只比较一次")
        void independentEqualInstances_shouldCompareEachIndependentObjectOnce() {
            final ChangeTracker tracker = tracker();
            final SharedGraphProbe sample = sharedGraph(REPRODUCTION_DEPTH, "");
            tracker.track(sample);
            counter.reset();
            replaceWithEqualInstances(sample);

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.isEmpty()).isTrue();
            assertThat(changeSet.getAllChanges()).isEmpty();
            assertThat(counter.count()).isEqualTo(REPRODUCTION_INDEPENDENT_OBJECTS);
        }
    }

    @Nested
    @DisplayName("有变更的共享子图")
    class ChangedSubgraph {

        @Test
        @DisplayName("末端叶子变更时各条应报告路径均保留")
        void changedLeaf_shouldKeepEveryReportedPath() {
            final ChangeTracker tracker = tracker();
            final SharedGraphProbe sample = sharedGraph(2, "");
            tracker.track(sample);
            counter.reset();
            replaceWithEqualInstances(sample);

            final SharedGraphProbe terminal = deepest(sample);
            terminal.value = new EqualsCountingValue(terminal.value.code() + "-changed", counter);

            final List<Change> leaves = tracker.calculateChanges().getLeafChanges();

            assertThat(leaves).extracting(Change::path).containsExactlyInAnyOrder(
                    "first.first.value",
                    "first.second.value",
                    "second.first.value",
                    "second.second.value");
        }

        @Test
        @DisplayName("循环与共享混合图应终止且不产生虚假变更")
        void cyclicAndSharedMixedGraph_shouldTerminateWithoutFalseChanges() {
            final ChangeTracker tracker = tracker();
            final MixedGraphProbe sample = new MixedGraphProbe();
            sample.cycle = cyclicGraph(REPRODUCTION_DEPTH, "");
            sample.shared = sharedGraph(2, "");
            tracker.track(sample);
            counter.reset();
            replaceWithEqualInstances(sample);

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.isEmpty()).isTrue();
            assertThat(changeSet.getLeafChanges()).isEmpty();
        }
    }

    @Nested
    @DisplayName("基线捕获与恢复")
    class BaselineRoundTrip {

        @Test
        @DisplayName("恢复基线后的首次计算仍复用无变更子图")
        void restoredBaseline_shouldStillReuseTheUnchangedSubgraph() {
            final TrackingCapabilityProvider provider = defaultProvider();
            final SharedGraphProbe sample = sharedGraph(REPRODUCTION_DEPTH, "");
            final ChangeTracker tracker = new ChangeTracker(provider.create());
            tracker.track(sample);
            final BaselineSnapshot baseline = tracker.captureBaseline();
            replaceWithEqualInstances(sample);

            final ChangeTracker restored = ChangeTracker.fromBaseline(provider.create(), baseline);
            counter.reset();
            final ChangeSet changeSet = restored.calculateChanges();

            assertThat(changeSet.isEmpty()).isTrue();
            assertThat(counter.count()).isEqualTo(REPRODUCTION_INDEPENDENT_OBJECTS);
        }

        @Test
        @DisplayName("恢复基线后仍报告共享子图各条路径的变更")
        void restoredBaseline_shouldStillReportEveryChangedPath() {
            final TrackingCapabilityProvider provider = defaultProvider();
            final SharedGraphProbe sample = sharedGraph(2, "");
            final ChangeTracker tracker = new ChangeTracker(provider.create());
            tracker.track(sample);
            final BaselineSnapshot baseline = tracker.captureBaseline();
            replaceWithEqualInstances(sample);
            final SharedGraphProbe terminal = deepest(sample);
            terminal.value = new EqualsCountingValue(terminal.value.code() + "-changed", counter);

            final ChangeTracker restored = ChangeTracker.fromBaseline(provider.create(), baseline);
            final List<Change> leaves = restored.calculateChanges().getLeafChanges();

            assertThat(leaves).extracting(Change::path).containsExactlyInAnyOrder(
                    "first.first.value",
                    "first.second.value",
                    "second.first.value",
                    "second.second.value");
        }
    }

    /**
     * 经 ServiceLoader 按服务实现类名发现默认 provider，并注册计数样本为值类型。
     *
     * @return 已注册 {@link EqualsCountingValue} 的默认 provider
     * @throws IllegalStateException 如果未发现默认 provider
     */
    private TrackingCapabilityProvider defaultProvider() {
        for (final TrackingCapabilityProvider provider : ServiceLoader.load(TrackingCapabilityProvider.class)) {
            if (DEFAULT_PROVIDER_CLASS_NAME.equals(provider.getClass().getName())) {
                provider.withValueType(EqualsCountingValue.class);
                return provider;
            }
        }
        throw new IllegalStateException("Default provider not discovered through ServiceLoader");
    }

    /**
     * 装配一个默认配置的真实追踪器。
     *
     * @return 真实链路的追踪器
     */
    private ChangeTracker tracker() {
        return new ChangeTracker(defaultProvider().create());
    }

    /**
     * 构造共享链：{@code depth} 层分支，每层 {@code first}/{@code second} 引用同一子节点实例，
     * 末端为带一个值字段的叶子。
     *
     * @param depth       分支层数，至少 1。
     * @param valueSuffix 值编码后缀，用于构造值不同的样本。
     * @return 链根对象，独立对象数为 {@code depth + 1}
     */
    private SharedGraphProbe sharedGraph(final int depth, final String valueSuffix) {
        SharedGraphProbe node = new SharedGraphProbe();
        node.value = new EqualsCountingValue("L" + depth + valueSuffix, counter);
        for (int level = depth - 1; level >= 0; level--) {
            final SharedGraphProbe branch = new SharedGraphProbe();
            branch.value = new EqualsCountingValue("L" + level + valueSuffix, counter);
            branch.first = node;
            branch.second = node;
            node = branch;
        }
        return node;
    }

    /**
     * 构造长度为 {@code length} 的循环链：末节点的 {@code next} 回指首节点。
     *
     * @param length      环节点数量，至少 1。
     * @param valueSuffix 值编码后缀。
     * @return 环的首个节点
     */
    private CyclicGraphProbe cyclicGraph(final int length, final String valueSuffix) {
        final CyclicGraphProbe head = new CyclicGraphProbe();
        head.value = new EqualsCountingValue("C0" + valueSuffix, counter);
        CyclicGraphProbe current = head;
        for (int index = 1; index < length; index++) {
            final CyclicGraphProbe next = new CyclicGraphProbe();
            next.value = new EqualsCountingValue("C" + index + valueSuffix, counter);
            current.next = next;
            current = next;
        }
        current.next = head;
        return head;
    }

    /**
     * 把共享链中每个节点的值替换为<b>独立且相等</b>的新实例，模拟「业务对象被重新赋值但值不变」。
     *
     * @param root 共享链根对象
     */
    private void replaceWithEqualInstances(final SharedGraphProbe root) {
        replaceValues(root, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * 递归替换共享链节点的值实例（按身份去重，支持共享结构）。
     *
     * @param node    当前节点，允许为 null（末端）
     * @param visited 已访问节点集
     */
    private void replaceValues(final SharedGraphProbe node, final Set<SharedGraphProbe> visited) {
        if (node == null || !visited.add(node)) {
            return;
        }
        node.value = new EqualsCountingValue(node.value.code(), counter);
        replaceValues(node.first, visited);
        replaceValues(node.second, visited);
    }

    /**
     * 把混合图的循环与共享部分的值替换为独立且相等的新实例。
     *
     * @param root 混合图根对象
     */
    private void replaceWithEqualInstances(final MixedGraphProbe root) {
        final Set<CyclicGraphProbe> visitedCycles = Collections.newSetFromMap(new IdentityHashMap<>());
        replaceCycleValues(root.cycle, visitedCycles);
        replaceWithEqualInstances(root.shared);
    }

    /**
     * 递归替换环节点的值实例（按身份去重，终止于环）。
     *
     * @param node    当前环节点，允许为 null
     * @param visited 已访问节点集
     */
    private void replaceCycleValues(final CyclicGraphProbe node, final Set<CyclicGraphProbe> visited) {
        if (node == null || !visited.add(node)) {
            return;
        }
        node.value = new EqualsCountingValue(node.value.code(), counter);
        replaceCycleValues(node.next, visited);
    }

    /**
     * 沿 {@code first} 走到共享链的末端节点。
     *
     * @param root 共享链根对象
     * @return 末端节点
     */
    private static SharedGraphProbe deepest(final SharedGraphProbe root) {
        SharedGraphProbe current = root;
        while (current.first != null) {
            current = current.first;
        }
        return current;
    }

    /**
     * 共享链探针：{@code first}/{@code second} 指向同一子节点表达共享。
     */
    static final class SharedGraphProbe {

        /**
         * 计数叶子值。
         */
        EqualsCountingValue value;

        /**
         * 第一个引用。
         */
        SharedGraphProbe first;

        /**
         * 第二个引用，与 {@link #first} 是同一实例时表达共享。
         */
        SharedGraphProbe second;
    }

    /**
     * 循环链探针：{@code next} 闭合回环。
     */
    static final class CyclicGraphProbe {

        /**
         * 计数叶子值。
         */
        EqualsCountingValue value;

        /**
         * 环内下一个节点。
         */
        CyclicGraphProbe next;
    }

    /**
     * 循环与共享混合探针。
     */
    static final class MixedGraphProbe {

        /**
         * 循环部分。
         */
        CyclicGraphProbe cycle;

        /**
         * 共享部分。
         */
        SharedGraphProbe shared;
    }
}
