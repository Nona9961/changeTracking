package com.nona.changeTracking.domain.capability;

import com.nona.changeTracking.domain.model.snapshot.ObjectNode;
import com.nona.changeTracking.domain.model.snapshot.PrimitiveNode;
import com.nona.changeTracking.domain.model.snapshot.ValueNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ComparisonContext} 的单表三态节点对状态单元测试。
 * <p>
 * 覆盖上下文侧语义：进入时<b>一次查询</b>同一份节点对状态，同时回答「这个节点对
 * 是否正在比较」（循环终止）与「是否已完整比较且确认没有变化」（复用跳过）；退出时把本次结论
 * （无变更且期间未发生循环截断 → 可复用，否则不可复用）写回同一状态。三态分别为
 * 「正在比较（{@code IN_PROGRESS}）」「已完成且无变更（{@code COMPLETED_UNCHANGED}）」
 * 「已完成但有变更（{@code COMPLETED_CHANGED}）」。
 * <p>
 * 与「两套登记」绑定的旧用例已按单表三态改写：不再存在独立的「结论查询」与「结论登记」入口，
 * 已登记无变更结论的节点对被一次查询命中即复用。每例自建前置状态（{@link #setUp()} 新建上下文），
 * 不依赖前序用例的产物。
 * <p>
 * 非空守卫口径：节点对相关方法为包内私有且调用方已保证两侧非空，不写重复守卫，
 * 因此本测试不以 null 断言 NPE；边界守卫（构造入口与 {@code pushField} 字段名边界）的断言
 * 归 {@code ComparisonContextUnitTest}。
 */
@DisplayName("ComparisonContext 单表三态节点对状态单元测试")
class ComparisonContextReuseUnitTest {

    /**
     * 每次测试新建的会话状态。
     */
    private ComparisonContext context;

    @BeforeEach
    void setUp() {
        context = new ComparisonContext();
    }

    @Nested
    @DisplayName("进入分发（一次查询同时回答循环与复用）")
    class EnterDispatch {

        @Test
        @DisplayName("未记录的节点对进入时应开始比较（置为正在比较）")
        void enterNodePair_unknownPair_shouldStartComparison() {
            assertThat(context.enterNodePair(node("a"), node("b"))).isTrue();

            assertThat(context.cycleTruncationCount()).isZero();
        }

        @Test
        @DisplayName("已登记无变更结论的节点对被一次查询命中即复用")
        void enterNodePair_completedUnchangedPair_shouldBeReusedByASingleQuery() {
            final ValueNode oldNode = node("a");
            final ValueNode newNode = node("b");
            context.enterNodePair(oldNode, newNode);
            context.exitNodePair(oldNode, newNode, true);

            assertThat(context.enterNodePair(oldNode, newNode)).isFalse();
            assertThat(context.cycleTruncationCount()).isZero();
        }

        @Test
        @DisplayName("正在比较的节点对再次进入应返回 false 并使截断计数加一")
        void enterNodePair_inProgressPair_shouldTruncateAndCountOnce() {
            final ValueNode oldNode = node("a");
            final ValueNode newNode = node("b");
            context.enterNodePair(oldNode, newNode);

            assertThat(context.enterNodePair(oldNode, newNode)).isFalse();
            assertThat(context.cycleTruncationCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("已完成但有变更的节点对再次进入应重新比较")
        void enterNodePair_completedChangedPair_shouldCompareAgain() {
            final ValueNode oldNode = node("a");
            final ValueNode newNode = node("b");
            context.enterNodePair(oldNode, newNode);
            context.exitNodePair(oldNode, newNode, false);

            assertThat(context.enterNodePair(oldNode, newNode)).isTrue();
        }
    }

    @Nested
    @DisplayName("退出更新（同一状态按本次结论迁移）")
    class ExitUpdate {

        @Test
        @DisplayName("无变更且无截断的退出应登记为可复用，且复用命中不改变状态")
        void exitNodePair_unchangedWithoutTruncation_shouldRecordAReusableConclusion() {
            final ValueNode oldNode = node("a");
            final ValueNode newNode = node("b");
            context.enterNodePair(oldNode, newNode);

            context.exitNodePair(oldNode, newNode, true);

            assertThat(context.enterNodePair(oldNode, newNode)).isFalse();
            assertThat(context.enterNodePair(oldNode, newNode)).as("复用命中不把状态改写为正在比较").isFalse();
            assertThat(context.cycleTruncationCount()).isZero();
        }

        @Test
        @DisplayName("有变更的退出应登记为不可复用，下次进入重新比较")
        void exitNodePair_changedResult_shouldNotBeReusable() {
            final ValueNode oldNode = node("a");
            final ValueNode newNode = node("b");
            context.enterNodePair(oldNode, newNode);

            context.exitNodePair(oldNode, newNode, false);

            assertThat(context.enterNodePair(oldNode, newNode)).isTrue();
        }

        @Test
        @DisplayName("期间发生过循环截断的结果不得复用，即使本次无变更")
        void exitNodePair_afterATruncation_shouldNotBeReusableEvenWhenUnchanged() {
            final ValueNode oldOuter = node("outer");
            final ValueNode newOuter = node("outer");
            final ValueNode oldInner = node("inner");
            final ValueNode newInner = node("inner");
            context.enterNodePair(oldOuter, newOuter);
            context.enterNodePair(oldInner, newInner);
            assertThat(context.enterNodePair(oldInner, newInner)).isFalse();

            context.exitNodePair(oldInner, newInner, true);
            context.exitNodePair(oldOuter, newOuter, true);

            assertThat(context.cycleTruncationCount()).isEqualTo(1);
            assertThat(context.enterNodePair(oldInner, newInner)).as("依赖截断的内层结论不得复用").isTrue();
            assertThat(context.enterNodePair(oldOuter, newOuter)).as("依赖截断的外层结论不得复用").isTrue();
        }

        @Test
        @DisplayName("两参退出应保守地登记为不可复用，下次进入重新比较")
        void exitNodePair_defaultOverload_shouldMarkThePairAsChanged() {
            final ValueNode oldNode = node("a");
            final ValueNode newNode = node("b");
            context.enterNodePair(oldNode, newNode);

            context.exitNodePair(oldNode, newNode);

            assertThat(context.enterNodePair(oldNode, newNode)).isTrue();
        }

        @Test
        @DisplayName("已登记的可复用结论不会因其他节点对的退出而丢失")
        void exitNodePair_shouldNotDropARecordedConclusion() {
            final ValueNode recordedOld = node("a");
            final ValueNode recordedNew = node("b");
            context.enterNodePair(recordedOld, recordedNew);
            context.exitNodePair(recordedOld, recordedNew, true);

            final ValueNode otherOld = node("c");
            final ValueNode otherNew = node("d");
            context.enterNodePair(otherOld, otherNew);
            context.exitNodePair(otherOld, otherNew, true);

            assertThat(context.enterNodePair(recordedOld, recordedNew)).isFalse();
        }

        @Test
        @DisplayName("状态不跨会话残留：新上下文不继承上一次比较的结论")
        void conclusions_shouldNotLeakBetweenSessions() {
            final ValueNode oldNode = node("a");
            final ValueNode newNode = node("b");
            context.enterNodePair(oldNode, newNode);
            context.exitNodePair(oldNode, newNode, true);

            final ComparisonContext nextSession = new ComparisonContext();

            assertThat(nextSession.enterNodePair(oldNode, newNode)).isTrue();
            assertThat(nextSession.cycleTruncationCount()).isZero();
        }
    }

    @Nested
    @DisplayName("循环截断计数")
    class CycleTruncationCounting {

        @Test
        @DisplayName("新建上下文时截断计数应为 0")
        void cycleTruncationCount_onFreshContext_shouldBeZero() {
            assertThat(context.cycleTruncationCount()).isZero();
        }

        @Test
        @DisplayName("首次登记节点对应不增加截断计数")
        void enterNodePair_firstEntry_shouldNotCountATruncation() {
            assertThat(context.enterNodePair(node("a"), node("b"))).isTrue();

            assertThat(context.cycleTruncationCount()).isZero();
        }

        @Test
        @DisplayName("同一节点对再次进入应返回 false 并使截断计数加一")
        void enterNodePair_repeatedPair_shouldTruncateAndCountOnce() {
            final ValueNode oldNode = node("a");
            final ValueNode newNode = node("b");
            context.enterNodePair(oldNode, newNode);

            assertThat(context.enterNodePair(oldNode, newNode)).isFalse();
            assertThat(context.cycleTruncationCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("退出后重新进入不重复计数，累计值保持")
        void exitNodePair_thenReentry_shouldKeepTheAccumulatedCount() {
            final ValueNode oldNode = node("a");
            final ValueNode newNode = node("b");
            context.enterNodePair(oldNode, newNode);
            context.enterNodePair(oldNode, newNode);
            context.exitNodePair(oldNode, newNode);

            assertThat(context.enterNodePair(oldNode, newNode)).isTrue();
            assertThat(context.cycleTruncationCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("不同节点对的截断应累计")
        void truncations_shouldAccumulateAcrossDistinctPairs() {
            for (int index = 0; index < 3; index++) {
                final ValueNode oldNode = node("old-" + index);
                final ValueNode newNode = node("new-" + index);
                context.enterNodePair(oldNode, newNode);
                context.enterNodePair(oldNode, newNode);
            }

            assertThat(context.cycleTruncationCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("内容相等但实例不同的节点对不算同一对，不触发截断")
        void contentEqualPairs_shouldNotTruncate() {
            context.enterNodePair(node("a"), node("b"));
            context.enterNodePair(node("a"), node("b"));

            assertThat(context.cycleTruncationCount()).isZero();
        }
    }

    @Nested
    @DisplayName("引用身份与路径隔离")
    class IdentityAndPathIsolation {

        @Test
        @DisplayName("节点对应按引用身份区分：内容相等但实例不同不算同一对")
        void enterNodePair_shouldCompareByReferenceNotContent() {
            final ValueNode oldOne = node("a");
            final ValueNode newOne = node("b");
            final ValueNode oldTwo = node("a");
            final ValueNode newTwo = node("b");
            context.enterNodePair(oldOne, newOne);
            context.exitNodePair(oldOne, newOne, true);

            assertThat(context.enterNodePair(oldTwo, newTwo)).isTrue();
        }

        @Test
        @DisplayName("可复用结论在路径段进入与退出之间保持")
        void reusedConclusion_shouldSurvivePathPushAndPop() {
            final ValueNode oldNode = node("a");
            final ValueNode newNode = node("b");
            context.enterNodePair(oldNode, newNode);
            context.exitNodePair(oldNode, newNode, true);

            context.pushField("items");
            context.pushItem("A", ComparisonContext.NO_OCCURRENCE);
            context.pop();
            context.pop();

            assertThat(context.enterNodePair(oldNode, newNode)).isFalse();
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