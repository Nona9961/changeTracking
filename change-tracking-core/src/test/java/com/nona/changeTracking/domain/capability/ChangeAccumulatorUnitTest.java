package com.nona.changeTracking.domain.capability;

import com.nona.changeTracking.domain.model.changeset.ChangeNode;
import com.nona.changeTracking.domain.model.changeset.FieldChangeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ChangeAccumulator} 单元测试：空态、按发现顺序收集与空列表契约（US01）。
 */
@DisplayName("ChangeAccumulator 按需变更收集单元测试")
class ChangeAccumulatorUnitTest {

    /**
     * 每次测试新建的收集器。
     */
    private ChangeAccumulator accumulator;

    @BeforeEach
    void setUp() {
        accumulator = new ChangeAccumulator();
    }

    @Nested
    @DisplayName("空态")
    class EmptyState {

        @Test
        @DisplayName("新建收集器应为空态")
        void newAccumulator_shouldBeEmpty() {
            assertThat(accumulator.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("空态的列表应为非空的空列表")
        void toList_onEmpty_shouldReturnNonNullEmptyList() {
            assertThat(accumulator.toList()).isNotNull().isEmpty();
        }
    }

    @Nested
    @DisplayName("按发现顺序收集")
    class DiscoveryOrder {

        @Test
        @DisplayName("收集一项后应非空且仅含该项")
        void add_singleChange_shouldBeRetained() {
            final ChangeNode change = change("a");

            accumulator.add(change);

            assertThat(accumulator.isEmpty()).isFalse();
            assertThat(accumulator.toList()).containsExactly(change);
        }

        @Test
        @DisplayName("收集多项应按加入顺序保留")
        void add_multipleChanges_shouldPreserveDiscoveryOrder() {
            final ChangeNode first = change("first");
            final ChangeNode second = change("second");
            final ChangeNode third = change("third");

            accumulator.add(first);
            accumulator.add(second);
            accumulator.add(third);

            assertThat(accumulator.toList()).containsExactly(first, second, third);
        }

        @Test
        @DisplayName("相同实例加入两次应按出现次数各保留一次（不去重）")
        void add_sameChangeTwice_shouldRetainBothOccurrences() {
            final ChangeNode change = change("a");

            accumulator.add(change);
            accumulator.add(change);

            assertThat(accumulator.toList()).containsExactly(change, change);
        }
    }

    @Nested
    @DisplayName("非法输入")
    class InvalidInput {

        @Test
        @DisplayName("收集 null 应抛 NullPointerException")
        void add_withNull_shouldThrowNullPointerException() {
            assertThatThrownBy(() -> accumulator.add(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    /**
     * 创建一个可区分的叶子变更节点。
     *
     * @param path 路径。
     * @return 叶子变更节点。
     */
    private static ChangeNode change(final String path) {
        return new FieldChangeNode(path, "old", "new");
    }
}
