package com.nona.changeTracking.domain.capability;

import com.nona.changeTracking.domain.model.snapshot.ObjectNode;
import com.nona.changeTracking.domain.model.snapshot.PrimitiveNode;
import com.nona.changeTracking.domain.model.snapshot.ValueNode;
import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ComparisonContext} 单表三态节点对状态表（{@code NodePairStates}）扩容与重散列的直接单元测试。
 * <p>
 * {@code NodePairStates} 是 {@link ComparisonContext} 的私有静态内部类，测试不能直接构造或读取容量；
 * 只能经包内可见的 {@link ComparisonContext#enterNodePair}、{@link ComparisonContext#exitNodePair}
 * 与 {@link ComparisonContext#cycleTruncationCount()} 间接驱动并观察可观察行为。
 * <p>
 * 容量参数：初始 16 槽、扩容倍数 4、阈值 = 容量 / 2（初始 8）。第 9 个不同节点对触发 16→64，
 * 第 33 个触发 64→256。扩容正确性的可观察判据为：扩容后先前登记的节点对仍按引用身份命中原状态
 * （{@code COMPLETED_UNCHANGED} 复用时 {@code enterNodePair} 返回 false 且不增加截断计数）、
 * 槽位的进入时截断计数与状态随容量一同迁移、扩容后的退出仍能定位到迁移后的槽位。
 * <p>
 * 每例在 {@link #setUp()} 新建独立会话，不依赖前序用例状态，也不污染后续用例。
 * 非空守卫口径与 {@code ComparisonContextReuseUnitTest} 一致：节点对方法为包内私有且调用方已保证
 * 两侧非空，本文件不对 null 断言异常。
 */
@DisplayName("ComparisonContext 节点对状态表扩容与重散列单元测试")
class NodePairStatesResizeUnitTest {

    /**
     * 每次测试新建的会话状态。
     */
    private ComparisonContext context;

    @BeforeEach
    void setUp() {
        context = new ComparisonContext();
    }

    @Nested
    @DisplayName("扩容触发与旧条目存活")
    class ResizeTriggerAndSurvival {

        @Test
        @DisplayName("恰好达到初始阈值（8 个不同节点对）时全部可命中，且不产生截断")
        void enterNodePair_atInitialThreshold_shouldKeepEveryPairReusable() {
            final ValueNode[][] pairs = distinctPairs(8);
            recordUnchanged(pairs);

            for (final ValueNode[] pair : pairs) {
                assertThat(context.enterNodePair(pair[0], pair[1]))
                        .as("未触发扩容时已登记无变更的节点对应被复用")
                        .isFalse();
            }
            assertThat(context.cycleTruncationCount())
                    .as("复用命中不产生循环截断")
                    .isZero();
        }

        @Test
        @DisplayName("命中已完成且无变化的节点对不应扩容：调用不产生分配")
        void enterNodePair_hitCompletedUnchanged_shouldNotResize() {
            final ValueNode[][] pairs = distinctPairs(8);
            recordUnchanged(pairs);

            final ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
            final long threadId = Thread.currentThread().getId();
            final long allocatedBefore = bean.getThreadAllocatedBytes(threadId);
            final boolean entered = context.enterNodePair(pairs[0][0], pairs[0][1]);
            final long allocatedAfter = bean.getThreadAllocatedBytes(threadId);

            assertThat(entered).as("复用命中应直接返回 false").isFalse();
            assertThat(allocatedAfter - allocatedBefore)
                    .as("命中已记录条目只应读取状态，不应扩容分配")
                    .isZero();
        }

        @Test
        @DisplayName("登记第 9 个不同节点对触发扩容后，先前 8 个仍可命中，未登记对仍视为新对")
        void enterNodePair_beyondThreshold_shouldKeepEarlierPairsReusable() {
            final ValueNode[][] pairs = distinctPairs(9);
            recordUnchanged(pairs);

            for (int index = 0; index < 8; index++) {
                assertThat(context.enterNodePair(pairs[index][0], pairs[index][1]))
                        .as("扩容前登记的节点对应在扩容后仍可命中")
                        .isFalse();
            }
            assertThat(context.enterNodePair(pairs[8][0], pairs[8][1]))
                    .as("触发扩容的第 9 个节点对自身也可命中")
                    .isFalse();

            final ValueNode unknownOld = node("unknown-old");
            final ValueNode unknownNew = node("unknown-new");
            assertThat(context.enterNodePair(unknownOld, unknownNew))
                    .as("未登记的节点对仍应视为新对")
                    .isTrue();
            assertThat(context.cycleTruncationCount())
                    .as("存活命中与新增登记都不产生循环截断")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("多级扩容后的可达性")
    class MultiLevelResize {

        @Test
        @DisplayName("40 个不同节点对触发两次扩容（16→64→256）后全部仍可命中")
        void enterNodePair_acrossTwoResizes_shouldKeepEveryPairReachable() {
            final ValueNode[][] pairs = distinctPairs(40);
            recordUnchanged(pairs);

            for (final ValueNode[] pair : pairs) {
                assertThat(context.enterNodePair(pair[0], pair[1]))
                        .as("两次扩容后先前登记的节点对仍应可命中")
                        .isFalse();
            }
            assertThat(context.cycleTruncationCount())
                    .as("两次扩容与随后命中都不产生循环截断")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("重散列后的引用身份语义")
    class RehashIdentitySemantics {

        @Test
        @DisplayName("扩容后按引用身份命中：同实例对复用；内容相等异实例对与交换顺序对均视为新对")
        void enterNodePair_afterResize_shouldMatchByReferenceIdentityNotContent() {
            final ValueNode[][] pairs = distinctPairs(9);
            recordUnchanged(pairs);
            final ValueNode[] reused = pairs[0];

            assertThat(context.enterNodePair(reused[0], reused[1]))
                    .as("扩容后同一实例对命中复用")
                    .isFalse();
            assertThat(context.cycleTruncationCount())
                    .as("复用命中不产生循环截断")
                    .isZero();

            final ValueNode sameContentOld = node("old-0");
            final ValueNode sameContentNew = node("new-0");
            assertThat(context.enterNodePair(sameContentOld, sameContentNew))
                    .as("内容相等但实例不同的节点对不命中原条目")
                    .isTrue();

            assertThat(context.enterNodePair(reused[1], reused[0]))
                    .as("交换两侧顺序的节点对不命中原条目")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("进入时截断计数随扩容迁移")
    class TruncationWindowMigration {

        @Test
        @DisplayName("跨扩容进入并按无变更退出：迁移后的进入计数与退出计数相符，结论保持可复用")
        void exitNodePair_afterResize_shouldReuseUnchangedConclusionWhenTruncationWindowUntouched() {
            final ValueNode truncatedOld = node("truncated-old");
            final ValueNode truncatedNew = node("truncated-new");
            context.enterNodePair(truncatedOld, truncatedNew);
            assertThat(context.enterNodePair(truncatedOld, truncatedNew))
                    .as("同一正在比较的节点对再次进入应被截断")
                    .isFalse();
            assertThat(context.cycleTruncationCount()).isEqualTo(1);

            final ValueNode inFlightOld = node("in-flight-old");
            final ValueNode inFlightNew = node("in-flight-new");
            assertThat(context.enterNodePair(inFlightOld, inFlightNew))
                    .as("截断计数为 1 时进入的节点对应记录进入计数 1")
                    .isTrue();

            final ValueNode[][] fillers = distinctPairs(7);
            for (final ValueNode[] filler : fillers) {
                assertThat(context.enterNodePair(filler[0], filler[1]))
                        .as("填充节点对用于触发扩容")
                        .isTrue();
            }

            context.exitNodePair(inFlightOld, inFlightNew, true);

            assertThat(context.enterNodePair(inFlightOld, inFlightNew))
                    .as("进入计数 1 与退出计数 1 相符，结论应可复用")
                    .isFalse();
            assertThat(context.cycleTruncationCount())
                    .as("退出与复用命中都不改变累计截断计数")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("跨扩容期间发生新截断：无变更退出也不可复用")
        void exitNodePair_afterResize_shouldNotReuseUnchangedConclusionWhenATruncationHappened() {
            final ValueNode truncatedOld = node("truncated-old");
            final ValueNode truncatedNew = node("truncated-new");
            context.enterNodePair(truncatedOld, truncatedNew);
            assertThat(context.enterNodePair(truncatedOld, truncatedNew)).isFalse();

            final ValueNode inFlightOld = node("in-flight-old");
            final ValueNode inFlightNew = node("in-flight-new");
            assertThat(context.enterNodePair(inFlightOld, inFlightNew))
                    .as("截断计数为 1 时进入的节点对应记录进入计数 1")
                    .isTrue();

            final ValueNode[][] fillers = distinctPairs(7);
            for (final ValueNode[] filler : fillers) {
                assertThat(context.enterNodePair(filler[0], filler[1]))
                        .as("填充节点对用于触发扩容")
                        .isTrue();
            }

            assertThat(context.enterNodePair(truncatedOld, truncatedNew))
                    .as("扩容后仍应命中正在比较的同一节点对")
                    .isFalse();
            assertThat(context.cycleTruncationCount()).isEqualTo(2);

            context.exitNodePair(inFlightOld, inFlightNew, true);

            assertThat(context.enterNodePair(inFlightOld, inFlightNew))
                    .as("进入计数 1 与退出计数 2 不符，结论应不可复用")
                    .isTrue();
            assertThat(context.cycleTruncationCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("扩容后退出定位")
    class ExitLocatesSlotsAfterResize {

        @Test
        @DisplayName("扩容后退出正在比较的节点对：命中迁移后槽位并就地登记为可复用")
        void exitNodePair_afterResize_shouldRecordReusableOnTheMigratedSlot() {
            final ValueNode inFlightOld = node("in-flight-old");
            final ValueNode inFlightNew = node("in-flight-new");
            assertThat(context.enterNodePair(inFlightOld, inFlightNew)).isTrue();

            final ValueNode[][] fillers = distinctPairs(8);
            for (final ValueNode[] filler : fillers) {
                assertThat(context.enterNodePair(filler[0], filler[1]))
                        .as("填充节点对用于触发扩容")
                        .isTrue();
            }

            context.exitNodePair(inFlightOld, inFlightNew, true);

            assertThat(context.enterNodePair(inFlightOld, inFlightNew))
                    .as("扩容后退出应命中迁移后的槽位并登记为可复用")
                    .isFalse();
            assertThat(context.cycleTruncationCount())
                    .as("命中复用而非正在比较，不产生截断")
                    .isZero();
        }

        @Test
        @DisplayName("扩容后退出正在比较的节点对：命中迁移后槽位并就地登记为不可复用")
        void exitNodePair_afterResize_shouldRecordChangedOnTheMigratedSlot() {
            final ValueNode inFlightOld = node("changed-old");
            final ValueNode inFlightNew = node("changed-new");
            assertThat(context.enterNodePair(inFlightOld, inFlightNew)).isTrue();

            final ValueNode[][] fillers = distinctPairs(8);
            for (final ValueNode[] filler : fillers) {
                assertThat(context.enterNodePair(filler[0], filler[1]))
                        .as("填充节点对用于触发扩容")
                        .isTrue();
            }

            context.exitNodePair(inFlightOld, inFlightNew, false);

            assertThat(context.enterNodePair(inFlightOld, inFlightNew))
                    .as("扩容后退出应命中迁移后的槽位并登记为不可复用")
                    .isTrue();
            assertThat(context.cycleTruncationCount())
                    .as("重新比较不产生截断")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("循环截断计数跨扩容口径")
    class CycleTruncationAcrossResize {

        @Test
        @DisplayName("扩容本身不改变累计截断计数，扩容后再次命中正在比较的节点对再加一")
        void cycleTruncationCount_shouldAccumulateAcrossResize() {
            final ValueNode cyclicOld = node("cyclic-old");
            final ValueNode cyclicNew = node("cyclic-new");
            context.enterNodePair(cyclicOld, cyclicNew);
            assertThat(context.enterNodePair(cyclicOld, cyclicNew)).isFalse();
            assertThat(context.cycleTruncationCount()).isEqualTo(1);

            final ValueNode[][] fillers = distinctPairs(8);
            for (final ValueNode[] filler : fillers) {
                assertThat(context.enterNodePair(filler[0], filler[1]))
                        .as("填充节点对用于触发扩容")
                        .isTrue();
            }
            assertThat(context.cycleTruncationCount())
                    .as("扩容不改变累计截断计数")
                    .isEqualTo(1);

            assertThat(context.enterNodePair(cyclicOld, cyclicNew))
                    .as("扩容后仍应命中正在比较的同一节点对")
                    .isFalse();
            assertThat(context.cycleTruncationCount())
                    .as("扩容后再次命中正在比较的节点对再加一")
                    .isEqualTo(2);
        }
    }

    /**
     * 构造指定数量的两两不同的节点对，行间不共享任何实例。
     *
     * @param count 节点对数量。
     * @return 每行是一对（旧侧节点, 新侧节点）的二维数组。
     */
    private static ValueNode[][] distinctPairs(final int count) {
        final ValueNode[][] pairs = new ValueNode[count][2];
        for (int index = 0; index < count; index++) {
            pairs[index][0] = node("old-" + index);
            pairs[index][1] = node("new-" + index);
        }
        return pairs;
    }

    /**
     * 逐个登记节点对并按无变更退出，使其成为可复用的已记录条目。
     *
     * @param pairs 待登记的节点对。
     */
    private void recordUnchanged(final ValueNode[][] pairs) {
        for (final ValueNode[] pair : pairs) {
            assertThat(context.enterNodePair(pair[0], pair[1]))
                    .as("未登记的节点对首次进入应开始比较")
                    .isTrue();
            context.exitNodePair(pair[0], pair[1], true);
        }
    }

    /**
     * 创建一个用于节点对身份区分的复杂对象节点。
     *
     * @param value 承载的字段值。
     * @return 一个 ObjectNode。
     */
    private static ValueNode node(final String value) {
        final Map<String, ValueNode> fields = new LinkedHashMap<>();
        fields.put("value", new PrimitiveNode(value));
        return new ObjectNode(fields);
    }
}