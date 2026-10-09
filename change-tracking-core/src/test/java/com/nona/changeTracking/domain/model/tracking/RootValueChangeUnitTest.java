package com.nona.changeTracking.domain.model.tracking;

import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.ValueChange;
import com.nona.changeTracking.domain.capability.TrackingConfiguration;
import com.nona.changeTracking.internal.capability.DefaultTrackingCapability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实根值变化（空路径原子变化）单元测试。
 * <p>
 * 追踪目标本身就是快照根：可变但不能脱水的 {@code AtomicInteger} 按当前值复制为基本值节点，
 * 数组按值语义成为数组根节点。根处的真实值变化以<b>空路径原子变化</b>表达，并在完整视图与叶子视图
 * 中同时出现；空路径只保留这一种形态。
 */
@DisplayName("真实根值变化单元测试")
class RootValueChangeUnitTest {

    /**
     * 建立默认装配的变更检测器。
     *
     * @return 变更检测器
     */
    private static ChangeTracker newTracker() {
        return new ChangeTracker(new DefaultTrackingCapability(TrackingConfiguration.empty()));
    }

    @Nested
    @DisplayName("基本值根")
    class PrimitiveRoot {

        @Test
        @DisplayName("AtomicInteger 根值变化：空路径原子变化带前后业务值")
        void atomicIntegerRoot_shouldReportEmptyPathValueChange() {
            final ChangeTracker tracker = newTracker();
            final AtomicInteger counter = new AtomicInteger(1);
            tracker.track(counter);
            counter.incrementAndGet();

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.getLeafChanges()).hasSize(1);
            final ValueChange rootChange = (ValueChange) changeSet.getLeafChanges().get(0);
            assertThat(rootChange.fullPath()).isEmpty();
            assertThat(rootChange.relativePath()).isEmpty();
            assertThat(rootChange.fieldName()).isNull();
            assertThat(rootChange.collectionFieldName()).isNull();
            assertThat(rootChange.isParentCollection()).isFalse();
            assertThat(rootChange.oldValue()).isEqualTo(1);
            assertThat(rootChange.newValue()).isEqualTo(2);
        }

        @Test
        @DisplayName("空路径原子变化同时出现在完整视图与叶子视图")
        void rootChange_shouldAppearInBothViews() {
            final ChangeTracker tracker = newTracker();
            final AtomicInteger counter = new AtomicInteger(1);
            tracker.track(counter);
            counter.incrementAndGet();

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.getAllChanges()).hasSize(1);
            assertThat(changeSet.getAllChanges().get(0).fullPath()).isEmpty();
            assertThat(changeSet.getLeafChanges()).hasSize(1);
            assertThat(changeSet.getLeafChanges().get(0).fullPath()).isEmpty();
        }

        @Test
        @DisplayName("根值恢复原值后无净变化")
        void rootRestoredValue_shouldHaveNoNetDifference() {
            final ChangeTracker tracker = newTracker();
            final AtomicInteger counter = new AtomicInteger(1);
            tracker.track(counter);
            counter.set(5);
            counter.set(1);

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("数组根")
    class ArrayRoot {

        @Test
        @DisplayName("数组根内容变化：空路径原子变化，载荷为两侧数组实例")
        void arrayRoot_shouldReportEmptyPathValueChangeWithArrayPayloads() {
            final ChangeTracker tracker = newTracker();
            final String[] codes = new String[]{"A", "B"};
            tracker.track(codes);
            codes[0] = "C";

            final ChangeSet changeSet = tracker.calculateChanges();

            assertThat(changeSet.getLeafChanges()).hasSize(1);
            final ValueChange rootChange = (ValueChange) changeSet.getLeafChanges().get(0);
            assertThat(rootChange.fullPath()).isEmpty();
            assertThat(rootChange.oldValue()).isEqualTo(new String[]{"A", "B"});
            assertThat(rootChange.newValue()).isEqualTo(new String[]{"C", "B"});
        }

        @Test
        @DisplayName("数组根顺序变化按值语义报告（顺序敏感），恢复原顺序后无净变化")
        void arrayRootOrderChange_shouldBeOrderSensitive() {
            final ChangeTracker tracker = newTracker();
            final int[] values = new int[]{1, 2, 3};
            tracker.track(values);
            values[0] = 3;
            values[2] = 1;

            assertThat(tracker.calculateChanges().getLeafChanges()).hasSize(1);

            final int[] restored = new int[]{1, 2, 3};
            final ChangeTracker secondTracker = newTracker();
            secondTracker.track(restored);
            restored[0] = 3;
            restored[2] = 1;
            restored[0] = 1;
            restored[2] = 3;

            assertThat(secondTracker.calculateChanges().isEmpty()).isTrue();
        }
    }
}
