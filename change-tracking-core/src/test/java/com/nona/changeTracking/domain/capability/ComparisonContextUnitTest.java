package com.nona.changeTracking.domain.capability;

import com.nona.changeTracking.domain.model.snapshot.ObjectNode;
import com.nona.changeTracking.domain.model.snapshot.PrimitiveNode;
import com.nona.changeTracking.domain.model.snapshot.ValueNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ComparisonContext} 单元测试：路径段栈的按需生成、槽位复用、退出恢复与活动节点对状态。
 * <p>
 * 覆盖 US05 的路径语义（字段段、集合项段、出现序、null 标识、标识文本按需准备且复用、退出清理）
 * 与循环终止的活动节点对状态（按引用身份、可重入、退出有效）。
 */
@DisplayName("ComparisonContext 会话状态单元测试")
class ComparisonContextUnitTest {

    /**
     * 每次测试新建的会话状态。
     */
    private ComparisonContext context;

    @BeforeEach
    void setUp() {
        context = new ComparisonContext();
        CountingIdentity.TO_STRING_CALLS.set(0);
    }

    @Nested
    @DisplayName("路径按需生成（US05）")
    class PathGeneration {

        @Test
        @DisplayName("空栈路径应为空字符串")
        void currentPath_withEmptyStack_shouldBeEmptyString() {
            assertThat(context.currentPath()).isEmpty();
        }

        @Test
        @DisplayName("单个字段段路径应为字段名")
        void currentPath_withSingleFieldSegment_shouldBeFieldName() {
            context.pushField("status");

            assertThat(context.currentPath()).isEqualTo("status");
        }

        @Test
        @DisplayName("嵌套字段段应以点号连接")
        void currentPath_withNestedFieldSegments_shouldJoinWithDots() {
            context.pushField("address");
            context.pushField("street");

            assertThat(context.currentPath()).isEqualTo("address.street");
        }

        @Test
        @DisplayName("字段段后接集合项段应使用方括号（不加点号）")
        void currentPath_withFieldThenItem_shouldUseBracketNotation() {
            context.pushField("items");
            context.pushItem(42L, ComparisonContext.NO_OCCURRENCE);

            assertThat(context.currentPath()).isEqualTo("items[42]");
        }

        @Test
        @DisplayName("集合项段后接字段段应在方括号后以点号连接")
        void currentPath_withItemThenField_shouldAppendAfterBracket() {
            context.pushField("items");
            context.pushItem("A", ComparisonContext.NO_OCCURRENCE);
            context.pushField("value");

            assertThat(context.currentPath()).isEqualTo("items[A].value");
        }

        @Test
        @DisplayName("出现序非 NO_OCCURRENCE 时应追加 #n 后缀")
        void currentPath_withOccurrence_shouldAppendOccurrenceSuffix() {
            context.pushField("items");
            context.pushItem("A", 2);

            assertThat(context.currentPath()).isEqualTo("items[A#2]");
        }

        @Test
        @DisplayName("null 标识应呈现为 null 文本")
        void currentPath_withNullIdentity_shouldRenderNullText() {
            context.pushField("map");
            context.pushItem(null, ComparisonContext.NO_OCCURRENCE);

            assertThat(context.currentPath()).isEqualTo("map[null]");
        }

        @Test
        @DisplayName("根为集合项时路径应以方括号开头")
        void currentPath_withItemAtRoot_shouldStartWithBracket() {
            context.pushItem("A", ComparisonContext.NO_OCCURRENCE);
            context.pushField("value");

            assertThat(context.currentPath()).isEqualTo("[A].value");
        }
    }

    @Nested
    @DisplayName("边界与槽位复用")
    class ReuseAndBoundaries {

        @Test
        @DisplayName("超过初始容量时应扩容并保持所有路径段")
        void currentPath_beyondInitialCapacity_shouldGrowAndKeepSegments() {
            final StringBuilder expected = new StringBuilder();
            for (int index = 0; index < 64; index++) {
                context.pushField("f" + index);
                if (index > 0) {
                    expected.append('.');
                }
                expected.append('f').append(index);
            }

            assertThat(context.currentPath()).isEqualTo(expected.toString());
        }

        @Test
        @DisplayName("标识文本应按需准备并在同一活动项内复用")
        void currentPath_shouldPrepareIdentityTextLazilyAndReuseIt() {
            context.pushField("items");
            context.pushItem(new CountingIdentity("A"), ComparisonContext.NO_OCCURRENCE);

            assertThat(context.currentPath()).isEqualTo("items[CountingIdentity[A]]");
            assertThat(context.currentPath()).isEqualTo("items[CountingIdentity[A]]");

            assertThat(CountingIdentity.TO_STRING_CALLS).hasValue(1);
        }

        @Test
        @DisplayName("退出集合项段后应清理标识与文本引用，不污染后续项")
        void pop_shouldClearIdentityAndTextBeforeReuse() {
            context.pushField("items");
            context.pushItem(new CountingIdentity("A"), ComparisonContext.NO_OCCURRENCE);
            assertThat(context.currentPath()).isEqualTo("items[CountingIdentity[A]]");
            context.pop();

            context.pushItem(new CountingIdentity("B"), ComparisonContext.NO_OCCURRENCE);

            assertThat(context.currentPath()).isEqualTo("items[CountingIdentity[B]]");
            assertThat(CountingIdentity.TO_STRING_CALLS).hasValue(2);
        }

        @Test
        @DisplayName("退出字段段后应恢复上一层路径")
        void pop_shouldRestorePreviousPath() {
            context.pushField("address");
            context.pushField("street");
            context.pop();

            assertThat(context.currentPath()).isEqualTo("address");
        }

        @Test
        @DisplayName("字段段复用集合项段槽位时不应保留上一层标识文本")
        void pushFieldAfterItemPop_shouldNotRetainStaleIdentity() {
            context.pushItem(new CountingIdentity("A"), 1);
            assertThat(context.currentPath()).isEqualTo("[CountingIdentity[A]#1]");
            context.pop();

            context.pushField("status");

            assertThat(context.currentPath()).isEqualTo("status");
            assertThat(CountingIdentity.TO_STRING_CALLS).hasValue(1);
        }
    }

    @Nested
    @DisplayName("活动节点对（循环终止）")
    class ActiveNodePairs {

        @Test
        @DisplayName("首次登记节点对返回 true")
        void enterNodePair_firstEntry_shouldReturnTrue() {
            assertThat(context.enterNodePair(node("a"), node("b"))).isTrue();
        }

        @Test
        @DisplayName("同一节点对再次登记返回 false（循环）")
        void enterNodePair_samePairAgain_shouldReturnFalse() {
            final ValueNode oldNode = node("a");
            final ValueNode newNode = node("b");
            context.enterNodePair(oldNode, newNode);

            assertThat(context.enterNodePair(oldNode, newNode)).isFalse();
        }

        @Test
        @DisplayName("退出节点对后可以重新登记")
        void exitNodePair_shouldAllowReentry() {
            final ValueNode oldNode = node("a");
            final ValueNode newNode = node("b");
            context.enterNodePair(oldNode, newNode);
            context.exitNodePair(oldNode, newNode);

            assertThat(context.enterNodePair(oldNode, newNode)).isTrue();
        }

        @Test
        @DisplayName("节点对应按引用身份区分，内容相等但实例不同不算同一对")
        void nodePairs_shouldCompareByReferenceNotContent() {
            final ValueNode oldOne = node("a");
            final ValueNode newOne = node("b");
            final ValueNode oldTwo = node("a");
            final ValueNode newTwo = node("b");

            assertThat(context.enterNodePair(oldOne, newOne)).isTrue();
            assertThat(context.enterNodePair(oldTwo, newTwo)).isTrue();

            context.exitNodePair(oldOne, newOne);

            assertThat(context.enterNodePair(oldTwo, newTwo)).isFalse();
        }

        @Test
        @DisplayName("null 节点应被拒绝")
        void enterNodePair_withNull_shouldThrowNullPointerException() {
            assertThatThrownBy(() -> context.enterNodePair(null, node("b")))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> context.enterNodePair(node("a"), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("非法路径操作")
    class InvalidOperations {

        @Test
        @DisplayName("推出空栈应抛 IllegalStateException")
        void pop_onEmptyStack_shouldThrowIllegalStateException() {
            assertThatThrownBy(() -> context.pop())
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("null 字段名应被拒绝")
        void pushField_withNull_shouldThrowNullPointerException() {
            assertThatThrownBy(() -> context.pushField(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    /**
     * 创建一个用于节点对身份区分的复杂对象节点。
     *
     * @param value 承载的字段值。
     * @return 一个 ObjectNode。
     */
    private static ValueNode node(final String value) {
        return new ObjectNode(Map.of("value", new PrimitiveNode(value)));
    }

    /**
     * 可计数 {@code toString} 调用的不可变值类型：用于验证标识文本按需准备与复用（AC05.1）。
     */
    static final class CountingIdentity {

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
         * @param value 值。
         */
        CountingIdentity(final String value) {
            this.value = value;
        }

        /**
         * 按值比较。
         *
         * @param other 待比较对象。
         * @return 值相同返回 true。
         */
        @Override
        public boolean equals(final Object other) {
            return other instanceof CountingIdentity that && this.value.equals(that.value);
        }

        /**
         * 值哈希。
         *
         * @return 值哈希。
         */
        @Override
        public int hashCode() {
            return this.value.hashCode();
        }

        /**
         * 计数并返回文本。
         *
         * @return 文本表示。
         */
        @Override
        public String toString() {
            TO_STRING_CALLS.incrementAndGet();
            return "CountingIdentity[" + this.value + "]";
        }
    }
}
