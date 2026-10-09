package com.nona.changeTracking.domain.capability;

import com.nona.changeTracking.domain.model.changeset.Change;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.ObjectChange;
import com.nona.changeTracking.domain.model.snapshot.CollectionNode;
import com.nona.changeTracking.domain.model.snapshot.ObjectNode;
import com.nona.changeTracking.domain.model.snapshot.PrimitiveNode;
import com.nona.changeTracking.domain.model.snapshot.ValueNode;
import com.nona.changeTracking.domain.model.snapshot.ValueNodeSnapshot;
import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 深链定位分配单元测试：定位构造按需进行、前缀被复用，分配不随深度出现逐节点累积。
 * <p>
 * 覆盖三类场景：深链最深叶子变更的分配上界（critical）、零变更深链遍历不累积定位链且不格式化标识
 * （critical）、深链最深叶子变更的路径取值不变（happy）。分配以当前线程分配字节数度量；
 * 两侧用独立且内容相等的节点树，使零变更遍历仍走完整比较路径。
 */
@DisplayName("深链定位分配单元测试")
class PathLocationAllocationUnitTest {

    /**
     * 分配上界口径用的深链层数。
     */
    private static final int DEEP_CHAIN_DEPTH = 128;

    /**
     * 分配增长口径用的浅链层数。
     */
    private static final int SHALLOW_CHAIN_DEPTH = 64;

    /**
     * 每次测量重复次数，取最小值以降低抖动。
     */
    private static final int MEASUREMENT_REPEATS = 9;

    /**
     * 预热轮数：让比较路径完成类加载与即时编译。
     */
    private static final int WARMUP_ROUNDS = 64;

    /**
     * 每次测试新建的比较策略。
     */
    private ValueNodeComparisonStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new ValueNodeComparisonStrategy();
        CountingId.TO_STRING_CALLS.set(0);
    }

    @Nested
    @DisplayName("深链变更的分配上界")
    class DeepChainAllocation {

        @Test
        @DisplayName("最深叶子变更的分配不超过零变更遍历的固定倍率（不逐节点重建定位链）")
        void deepestLeafChange_shouldNotAccumulateLocationChainsPerNode() {
            warmUp();
            final ValueNode oldNode = fieldChain(DEEP_CHAIN_DEPTH, false);
            final ValueNode unchangedNode = fieldChain(DEEP_CHAIN_DEPTH, false);
            final ValueNode changedNode = fieldChain(DEEP_CHAIN_DEPTH, true);

            final long zeroChangeBytes = minimumAllocatedBytes(
                    () -> strategy.compare(new ValueNodeSnapshot(oldNode), new ValueNodeSnapshot(unchangedNode)));
            final long changedBytes = minimumAllocatedBytes(
                    () -> strategy.compare(new ValueNodeSnapshot(oldNode), new ValueNodeSnapshot(changedNode)));

            assertThat(changedBytes).isLessThan(zeroChangeBytes * 30);
        }

        @Test
        @DisplayName("最深叶子变更仍按深度逐段拼接完整路径")
        void deepestLeafChange_shouldStillReportTheDeepestFullPath() {
            final List<Change> root = strategy.compare(
                    new ValueNodeSnapshot(fieldChain(DEEP_CHAIN_DEPTH, false)),
                    new ValueNodeSnapshot(fieldChain(DEEP_CHAIN_DEPTH, true)));
            final ChangeSet changeSet = new ChangeSet(List.of(new ObjectChange(new Object(), root)));

            final List<Change> leaves = changeSet.getLeafChanges();

            assertThat(leaves).hasSize(1);
            assertThat(leaves.get(0).fullPath()).isEqualTo(expectedDeepestStreetPath(DEEP_CHAIN_DEPTH));
            assertThat(leaves.get(0).relativePath()).isEqualTo("street");
            assertThat(leaves.get(0).fieldName()).isEqualTo("street");
        }

        /**
         * 重新测量零变更遍历分配随深度的增长：遍历本身按节点数线性增长，定位链不得累积。
         */
        @Test
        @DisplayName("零变更遍历分配随深度线性增长，不累积逐节点定位链")
        void zeroChangeTraversal_shouldGrowLinearlyWithDepth() {
            warmUp();
            final ValueNode shallow = fieldChain(SHALLOW_CHAIN_DEPTH, false);
            final ValueNode shallowOther = fieldChain(SHALLOW_CHAIN_DEPTH, false);
            final ValueNode deep = fieldChain(DEEP_CHAIN_DEPTH, false);
            final ValueNode deepOther = fieldChain(DEEP_CHAIN_DEPTH, false);

            final long shallowBytes = minimumAllocatedBytes(
                    () -> strategy.compare(new ValueNodeSnapshot(shallow), new ValueNodeSnapshot(shallowOther)));
            final long deepBytes = minimumAllocatedBytes(
                    () -> strategy.compare(new ValueNodeSnapshot(deep), new ValueNodeSnapshot(deepOther)));

            assertThat(deepBytes).isLessThan(shallowBytes * 3);
        }
    }

    @Nested
    @DisplayName("零变更遍历不渲染路径与标识")
    class ZeroChangeNoRendering {

        @Test
        @DisplayName("深层嵌套集合的内容相等遍历不调用标识文本化")
        void zeroChangeOnDeepNestedCollections_shouldNotFormatAnyIdentityText() {
            final List<Change> root = strategy.compare(
                    new ValueNodeSnapshot(collectionChain(DEEP_CHAIN_DEPTH, false)),
                    new ValueNodeSnapshot(collectionChain(DEEP_CHAIN_DEPTH, false)));

            assertThat(root).isEmpty();
            assertThat(CountingId.TO_STRING_CALLS).hasValue(0);
        }
    }

    /**
     * 预热测量路径：让比较方法在测量前完成类加载与即时编译。
     */
    private void warmUp() {
        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            strategy.compare(new ValueNodeSnapshot(fieldChain(8, false)),
                    new ValueNodeSnapshot(fieldChain(8, true)));
            strategy.compare(new ValueNodeSnapshot(fieldChain(8, false)),
                    new ValueNodeSnapshot(fieldChain(8, false)));
        }
    }

    /**
     * 重复测量给定动作在当前线程分配的字节数，返回最小值。
     *
     * @param action 待测动作
     * @return 重复测量中最小的分配字节数
     */
    private long minimumAllocatedBytes(final Runnable action) {
        long minimum = Long.MAX_VALUE;
        for (int repeat = 0; repeat < MEASUREMENT_REPEATS; repeat++) {
            minimum = Math.min(minimum, allocatedBytes(action));
        }
        return minimum;
    }

    /**
     * 测量当前线程执行给定动作期间分配的字节数。
     *
     * @param action 待测动作
     * @return 分配字节数
     */
    private long allocatedBytes(final Runnable action) {
        final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        threads.setThreadAllocatedMemoryEnabled(true);
        final long before = threads.getThreadAllocatedBytes(Thread.currentThread().threadId());
        action.run();
        return threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
    }

    /**
     * 建立字段嵌套链：每层含 {@code city}/{@code street} 字段与指向下一层的 {@code next} 字段，
     * 最内层只含 {@code city}/{@code street}。
     *
     * @param depth           嵌套层数，至少 0
     * @param deepestChanged  最内层 {@code street} 是否取变更值
     * @return 链根对象节点
     */
    private static ObjectNode fieldChain(final int depth, final boolean deepestChanged) {
        final Map<String, ValueNode> fields = new LinkedHashMap<>();
        fields.put("city", new PrimitiveNode("c"));
        if (depth == 0) {
            fields.put("street", new PrimitiveNode(deepestChanged ? "changed" : "s"));
            return new ObjectNode(fields);
        }
        fields.put("street", new PrimitiveNode("s"));
        fields.put("next", fieldChain(depth - 1, deepestChanged));
        return new ObjectNode(fields);
    }

    /**
     * 建立集合嵌套链：每层含一个 {@code child} 集合字段，集合内唯一项带计数标识并指向下一层。
     *
     * @param depth           嵌套层数，至少 1
     * @param deepestChanged  最内层的载荷值是否取变更值
     * @return 链根对象节点
     */
    private static ObjectNode collectionChain(final int depth, final boolean deepestChanged) {
        final Map<String, ValueNode> innermost = new LinkedHashMap<>();
        innermost.put("value", new PrimitiveNode(deepestChanged ? "changed" : "v"));
        ValueNode current = new ObjectNode(innermost, new CountingId("leaf"));
        for (int level = depth - 1; level >= 0; level--) {
            final Map<String, ValueNode> fields = new LinkedHashMap<>();
            fields.put("child", new CollectionNode(List.of(current)));
            current = new ObjectNode(fields, new CountingId("level" + level));
        }
        return (ObjectNode) current;
    }

    /**
     * 计算最深叶子完整路径：{@code depth} 个 {@code next} 段后接 {@code street}。
     *
     * @param depth 嵌套层数
     * @return 最深叶子完整路径
     */
    private static String expectedDeepestStreetPath(final int depth) {
        final StringBuilder expected = new StringBuilder();
        for (int level = 0; level < depth; level++) {
            expected.append("next.");
        }
        return expected.append("street").toString();
    }

    /**
     * 相等语义基于值、文本表示计入计数的不可变标识：用于验证零变更遍历不格式化标识文本。
     */
    static final class CountingId {

        /**
         * {@code toString} 调用计数。
         */
        static final AtomicInteger TO_STRING_CALLS = new AtomicInteger();

        /**
         * 值。
         */
        private final String value;

        /**
         * 创建标识。
         *
         * @param value 值
         */
        CountingId(final String value) {
            this.value = value;
        }

        /**
         * 按值比较。
         *
         * @param other 待比较对象
         * @return 值相同返回 true
         */
        @Override
        public boolean equals(final Object other) {
            return other instanceof CountingId that && this.value.equals(that.value);
        }

        /**
         * 值哈希。
         *
         * @return 值哈希
         */
        @Override
        public int hashCode() {
            return this.value.hashCode();
        }

        /**
         * 计数并返回文本。
         *
         * @return 文本表示
         */
        @Override
        public String toString() {
            TO_STRING_CALLS.incrementAndGet();
            return "CountingId[" + this.value + "]";
        }
    }
}
