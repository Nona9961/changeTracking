package com.nona.changeTracking.domain.capability;

import com.nona.changeTracking.domain.model.changeset.ChangeLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ActivePath} 活动路径单元测试：路径与定位语义、按需准备、前缀复用与边界。
 * <p>
 * 覆盖三类场景：路径语义与定位取值（happy）、标识文本按需准备与前缀/同深度复用（critical）、
 * 空栈推出与 null 字段名等失败路径（fail）；每例由 {@link #setUp()} 自建前置状态，
 * 不依赖前序用例产物。
 */
@DisplayName("ActivePath 活动路径单元测试")
class ActivePathUnitTest {

    /**
     * 每次测试新建的活动路径。
     */
    private ActivePath path;

    @BeforeEach
    void setUp() {
        path = new ActivePath();
        CountingIdentity.TO_STRING_CALLS.set(0);
    }

    @Nested
    @DisplayName("路径与定位语义")
    class PathAndLocationSemantics {

        @Test
        @DisplayName("空栈的完整路径应为空串，定位为根定位")
        void emptyStack_shouldYieldEmptyPathAndRootLocation() {
            assertThat(path.currentPath()).isEmpty();

            final ChangeLocation location = path.currentLocation();

            assertThat(location.fullPath()).isEmpty();
            assertThat(location.relativePath()).isEmpty();
            assertThat(location.fieldName()).isNull();
            assertThat(location.collectionFieldName()).isNull();
            assertThat(location.isParentCollection()).isFalse();
        }

        @Test
        @DisplayName("嵌套字段段的完整路径以点号连接，相对路径只含本段")
        void nestedFieldSegments_shouldJoinWithDots() {
            path.pushField("address");
            path.pushField("street");

            final ChangeLocation location = path.currentLocation();

            assertThat(path.currentPath()).isEqualTo("address.street");
            assertThat(location.fullPath()).isEqualTo("address.street");
            assertThat(location.relativePath()).isEqualTo("street");
            assertThat(location.fieldName()).isEqualTo("street");
            assertThat(location.collectionFieldName()).isNull();
        }

        @Test
        @DisplayName("字段段后接集合项段使用方括号且不加点号")
        void fieldThenItem_shouldUseBracketNotation() {
            path.pushField("items");
            path.pushItem("A", ComparisonContext.NO_OCCURRENCE);

            final ChangeLocation location = path.currentLocation();

            assertThat(path.currentPath()).isEqualTo("items[A]");
            assertThat(location.relativePath()).isEqualTo("[A]");
            assertThat(location.fieldName()).isNull();
            assertThat(location.collectionFieldName()).isEqualTo("items");
            assertThat(location.isParentCollection()).isTrue();
        }

        @Test
        @DisplayName("集合项段后接字段段在方括号后以点号连接并继承集合归属")
        void itemThenField_shouldInheritTheCollectionFieldName() {
            path.pushField("items");
            path.pushItem("A", 2);
            path.pushField("quantity");

            final ChangeLocation location = path.currentLocation();

            assertThat(path.currentPath()).isEqualTo("items[A#2].quantity");
            assertThat(location.fullPath()).isEqualTo("items[A#2].quantity");
            assertThat(location.relativePath()).isEqualTo("quantity");
            assertThat(location.fieldName()).isEqualTo("quantity");
            assertThat(location.collectionFieldName()).isEqualTo("items");
            assertThat(location.isParentCollection()).isFalse();
        }

        @Test
        @DisplayName("根集合下的集合项不伪造集合字段名")
        void rootItem_shouldNotInventACollectionFieldName() {
            path.pushItem("A", ComparisonContext.NO_OCCURRENCE);

            final ChangeLocation location = path.currentLocation();

            assertThat(location.fullPath()).isEqualTo("[A]");
            assertThat(location.fieldName()).isNull();
            assertThat(location.collectionFieldName()).isNull();
            assertThat(location.isParentCollection()).isTrue();
        }

        @Test
        @DisplayName("嵌套集合项的相对路径只含自身段，集合归属取最近的集合字段")
        void nestedCollectionItem_shouldKeepOnlyItsOwnSegmentAsRelativePath() {
            path.pushField("items");
            path.pushItem(200, ComparisonContext.NO_OCCURRENCE);
            path.pushField("subItems");
            path.pushItem(101, ComparisonContext.NO_OCCURRENCE);

            final ChangeLocation location = path.currentLocation();

            assertThat(location.fullPath()).isEqualTo("items[200].subItems[101]");
            assertThat(location.relativePath()).isEqualTo("[101]");
            assertThat(location.collectionFieldName()).isEqualTo("subItems");
        }

        @Test
        @DisplayName("超过初始容量的深链应扩容并保持全部路径段")
        void beyondInitialCapacity_shouldGrowAndKeepEverySegment() {
            final StringBuilder expected = new StringBuilder();
            for (int depth = 0; depth < 64; depth++) {
                path.pushField("f" + depth);
                if (depth > 0) {
                    expected.append('.');
                }
                expected.append('f').append(depth);
            }

            assertThat(path.currentPath()).isEqualTo(expected.toString());
            assertThat(path.currentLocation().fullPath()).isEqualTo(expected.toString());
            assertThat(path.currentLocation().relativePath()).isEqualTo("f63");
        }
    }

    @Nested
    @DisplayName("标识文本按需准备")
    class LazyIdentityText {

        @Test
        @DisplayName("压入集合项段不字符串化标识，取定位或路径时才准备文本")
        void pushingAnItemSegment_shouldNotFormatItsIdentityText() {
            path.pushField("items");
            path.pushItem(new CountingIdentity("A"), ComparisonContext.NO_OCCURRENCE);

            assertThat(CountingIdentity.TO_STRING_CALLS).hasValue(0);

            assertThat(path.currentLocation().fullPath()).isEqualTo("items[CountingIdentity[A]]");
            assertThat(CountingIdentity.TO_STRING_CALLS).hasValue(1);
        }

        @Test
        @DisplayName("定位与路径共享同一份按需准备的标识文本")
        void currentLocationAndCurrentPath_shouldShareThePreparedIdentityText() {
            path.pushField("items");
            path.pushItem(new CountingIdentity("A"), ComparisonContext.NO_OCCURRENCE);

            assertThat(path.currentLocation().fullPath()).isEqualTo("items[CountingIdentity[A]]");
            assertThat(path.currentPath()).isEqualTo("items[CountingIdentity[A]]");

            assertThat(CountingIdentity.TO_STRING_CALLS).hasValue(1);
        }

        @Test
        @DisplayName("退出集合项段清理标识与文本引用，不污染后续项")
        void pop_shouldClearTheIdentityTextBeforeSlotReuse() {
            path.pushField("items");
            path.pushItem(new CountingIdentity("A"), ComparisonContext.NO_OCCURRENCE);
            assertThat(path.currentLocation().fullPath()).isEqualTo("items[CountingIdentity[A]]");
            path.pop();

            path.pushItem(new CountingIdentity("B"), ComparisonContext.NO_OCCURRENCE);

            assertThat(path.currentLocation().fullPath()).isEqualTo("items[CountingIdentity[B]]");
            assertThat(CountingIdentity.TO_STRING_CALLS).hasValue(2);
        }
    }

    @Nested
    @DisplayName("前缀复用")
    class PrefixReuse {

        @Test
        @DisplayName("同一深度重复取定位返回同一实例")
        void repeatedCurrentLocationAtTheSameDepth_shouldReturnTheSameInstance() {
            path.pushField("address");
            path.pushField("street");

            assertThat(path.currentLocation()).isSameAs(path.currentLocation());
        }

        @Test
        @DisplayName("空栈重复取定位返回同一声明的根定位")
        void repeatedRootLocation_shouldReturnTheSameInstance() {
            assertThat(path.currentLocation()).isSameAs(path.currentLocation());
        }

        @Test
        @DisplayName("深入一层后回退，祖先定位实例仍被复用且取值不变")
        void deeperThenPopped_shouldReuseTheAncestorLocation() {
            path.pushField("address");
            final ChangeLocation address = path.currentLocation();
            path.pushField("street");
            final ChangeLocation street = path.currentLocation();

            assertThat(street.fullPath()).isEqualTo("address.street");
            assertThat(address.fullPath()).isEqualTo("address");

            path.pop();

            assertThat(path.currentLocation()).isSameAs(address);
        }

        @Test
        @DisplayName("深链每层取定位后逐层回退，各层实例均被复用")
        void deepChainWalkBack_shouldReuseEveryLevelInstance() {
            final ChangeLocation[] built = new ChangeLocation[16];
            for (int depth = 0; depth < built.length; depth++) {
                path.pushField("level" + depth);
                built[depth] = path.currentLocation();
            }

            for (int depth = built.length - 1; depth >= 0; depth--) {
                assertThat(path.currentLocation()).isSameAs(built[depth]);
                path.pop();
            }
        }

        @Test
        @DisplayName("同深度压入不同段后取定位反映新段，不返回旧缓存取值")
        void pushingADifferentSegment_shouldDeriveTheNewLocation() {
            path.pushField("first");
            assertThat(path.currentLocation().fullPath()).isEqualTo("first");
            path.pop();

            path.pushField("second");

            assertThat(path.currentLocation().fullPath()).isEqualTo("second");
            assertThat(path.currentLocation().relativePath()).isEqualTo("second");
        }
    }

    @Nested
    @DisplayName("边界与相邻值")
    class BoundariesAndNeighbours {

        @Test
        @DisplayName("省略出现序与显式 NO_OCCURRENCE 等价，相邻出现序 1 与 2 渲染不同后缀")
        void occurrenceBoundaries_shouldRenderDistinctSuffixes() {
            path.pushField("items");
            path.pushItem("A", ComparisonContext.NO_OCCURRENCE);
            assertThat(path.currentLocation().fullPath()).isEqualTo("items[A]");
            path.pop();

            path.pushItem("A", 1);
            assertThat(path.currentLocation().fullPath()).isEqualTo("items[A#1]");
            path.pop();

            path.pushItem("A", 2);

            assertThat(path.currentLocation().fullPath()).isEqualTo("items[A#2]");
        }

        @Test
        @DisplayName("极大出现序按下标后缀渲染，不丢失后缀")
        void largeOccurrence_shouldStillRenderTheSuffix() {
            path.pushField("items");
            path.pushItem("A", Integer.MAX_VALUE);

            assertThat(path.currentLocation().fullPath())
                    .isEqualTo("items[A#" + Integer.MAX_VALUE + "]");
        }

        @Test
        @DisplayName("null 标识渲染为 null 文本，不抛异常")
        void nullIdentity_shouldRenderNullText() {
            path.pushField("map");
            path.pushItem(null, ComparisonContext.NO_OCCURRENCE);

            assertThat(path.currentLocation().fullPath()).isEqualTo("map[null]");
        }

        @Test
        @DisplayName("集合项段可作父段，嵌套集合项按既有规则拼接")
        void itemAsParent_shouldComposeTheNestedItemPath() {
            path.pushField("items");
            path.pushItem(1, ComparisonContext.NO_OCCURRENCE);
            path.pushItem(2, ComparisonContext.NO_OCCURRENCE);

            final ChangeLocation location = path.currentLocation();

            assertThat(location.fullPath()).isEqualTo("items[1][2]");
            assertThat(location.relativePath()).isEqualTo("[2]");
            assertThat(location.isParentCollection()).isTrue();
        }
    }

    @Nested
    @DisplayName("失败路径")
    class FailurePaths {

        @Test
        @DisplayName("空栈推出应抛 IllegalStateException")
        void pop_onEmptyStack_shouldThrowIllegalStateException() {
            assertThatThrownBy(() -> path.pop())
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("空栈推出失败后路径状态保持不变")
        void pop_onEmptyStack_shouldNotChangeThePathState() {
            assertThatThrownBy(() -> path.pop())
                    .isInstanceOf(IllegalStateException.class);

            assertThat(path.currentPath()).isEmpty();
            assertThat(path.currentLocation().fullPath()).isEmpty();
        }

        @Test
        @DisplayName("null 字段名应被拒绝")
        void pushField_withNull_shouldThrowNullPointerException() {
            assertThatThrownBy(() -> path.pushField(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null 字段名被拒绝后路径状态保持不变")
        void pushField_withNull_shouldNotChangeThePathState() {
            path.pushField("status");
            final ChangeLocation before = path.currentLocation();

            assertThatThrownBy(() -> path.pushField(null))
                    .isInstanceOf(NullPointerException.class);

            assertThat(path.currentPath()).isEqualTo("status");
            assertThat(path.currentLocation()).isSameAs(before);
        }
    }

    /**
     * 可计数 {@code toString} 调用的不可变值类型：用于验证标识文本按需准备与复用。
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
         * @param value 值
         */
        CountingIdentity(final String value) {
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
            return other instanceof CountingIdentity that && this.value.equals(that.value);
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
            return "CountingIdentity[" + this.value + "]";
        }
    }
}
