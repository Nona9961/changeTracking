package com.nona.changeTracking.domain.model.changeset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ChangeSet} 变更集单元测试。
 * <p>
 * 覆盖变更集的构造契约（非空参数、元素非空、防御性复制、不可变）、空集语义（无净变化时两视图为空且
 * {@code isEmpty()} 为真）与多目标结果的顺序保留。
 */
@DisplayName("ChangeSet 变更集单元测试")
class ChangeSetUnitTest {

    @Nested
    @DisplayName("空集语义")
    class EmptyChangeSet {

        @Test
        @DisplayName("空比较列表表达无变化：isEmpty 为真且两个视图均为空")
        void emptyChangeSet_shouldReportNoChanges() {
            final ChangeSet changeSet = new ChangeSet(List.of());

            assertThat(changeSet.isEmpty()).isTrue();
            assertThat(changeSet.getAllChanges()).isEmpty();
            assertThat(changeSet.getLeafChanges()).isEmpty();
        }

        @Test
        @DisplayName("空变更集的两个视图都是只读空列表")
        void emptyChangeSet_viewsShouldBeReadOnly() {
            final ChangeSet changeSet = new ChangeSet(List.of());

            assertThatThrownBy(() -> changeSet.getAllChanges().add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> changeSet.getLeafChanges().add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("有变化的目标")
    class NonEmptyChangeSet {

        @Test
        @DisplayName("含目标结果时 isEmpty 为假，且保留目标结果的顺序")
        void nonEmptyChangeSet_shouldKeepObjectChangeOrder() {
            final ObjectChange first = new ObjectChange(new Object(),
                    List.of(ChangeTreeFixtures.value(ChangeTreeFixtures.field("status"), "a", "b")));
            final ObjectChange second = new ObjectChange(new Object(),
                    List.of(ChangeTreeFixtures.value(ChangeTreeFixtures.field("name"), "c", "d")));

            final ChangeSet changeSet = new ChangeSet(List.of(first, second));

            assertThat(changeSet.isEmpty()).isFalse();
            assertThat(changeSet.changes()).containsExactly(first, second);
        }
    }

    @Nested
    @DisplayName("失败路径与不可变边界")
    class FailurePaths {

        @Test
        @DisplayName("拒绝 null 目标结果列表")
        void nullChanges_shouldBeRejected() {
            assertThatThrownBy(() -> new ChangeSet(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("拒绝列表中的 null 元素")
        void nullElement_shouldBeRejected() {
            final List<ObjectChange> withNull = new ArrayList<>();
            withNull.add(null);

            assertThatThrownBy(() -> new ChangeSet(withNull))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("构造后修改入参列表不影响变更集内容")
        void constructor_shouldDefensivelyCopyTargets() {
            final ObjectChange objectChange = new ObjectChange(new Object(),
                    List.of(ChangeTreeFixtures.value(ChangeTreeFixtures.field("status"), "a", "b")));
            final List<ObjectChange> source = new ArrayList<>(List.of(objectChange));

            final ChangeSet changeSet = new ChangeSet(source);
            source.clear();

            assertThat(changeSet.changes()).containsExactly(objectChange);
        }

        @Test
        @DisplayName("目标结果列表不可修改")
        void changes_shouldBeReadOnly() {
            final ChangeSet changeSet = new ChangeSet(List.of(new ObjectChange(new Object(),
                    List.of(ChangeTreeFixtures.value(ChangeTreeFixtures.field("status"), "a", "b")))));

            assertThatThrownBy(() -> changeSet.changes().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
