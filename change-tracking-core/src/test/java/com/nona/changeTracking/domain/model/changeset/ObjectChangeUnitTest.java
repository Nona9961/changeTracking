package com.nona.changeTracking.domain.model.changeset;

import com.nona.changeTracking.domain.model.snapshot.PrimitiveNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ObjectChange} 单目标变更单元测试。
 * <p>
 * 覆盖结果组织边界：绑定原追踪目标身份、直接持有目标根下的非空结果列表（无人工根容器）、真实根值变化
 * 以空路径原子变化保留；以及失败路径「空单目标结果在构造时拒绝」与「空路径分组（人工根容器）被拒绝」
 * （失败条件与 AC01-4、AC01-5）。
 */
@DisplayName("ObjectChange 单目标变更单元测试")
class ObjectChangeUnitTest {

    @Nested
    @DisplayName("合法结果组织")
    class ValidOrganization {

        @Test
        @DisplayName("绑定原追踪目标身份：target 为同一实例")
        void target_shouldBeTheTrackedInstance() {
            final Object target = new Object();
            final ValueChange status = ChangeTreeFixtures.value(ChangeTreeFixtures.field("status"), "CREATED", "PAID");

            final ObjectChange objectChange = new ObjectChange(target, List.of(status));

            assertThat(objectChange.target()).isSameAs(target);
            assertThat(objectChange.changes()).containsExactly(status);
        }

        @Test
        @DisplayName("直接持有目标根下的结果列表，不插入人工根容器")
        void changes_shouldBeTheRootLevelResults() {
            final ValueChange status = ChangeTreeFixtures.value(ChangeTreeFixtures.field("status"), "CREATED", "PAID");
            final ContainerChange items = new ContainerChange(
                    ChangeTreeFixtures.field("items"),
                    List.of(ChangeTreeFixtures.added(ChangeTreeFixtures.item("items", 9), new PrimitiveNode("SKU-9"))));

            final ObjectChange objectChange = new ObjectChange(new Object(), List.of(status, items));

            assertThat(objectChange.changes()).hasSize(2);
            assertThat(objectChange.changes().get(0)).isSameAs(status);
            assertThat(objectChange.changes().get(1)).isSameAs(items);
        }

        @Test
        @DisplayName("真实根值变化：空路径原子变化是合法顶层结果")
        void rootValueChange_asAtomicChange_shouldBeAccepted() {
            final ValueChange rootChange = ChangeTreeFixtures.value(ChangeTreeFixtures.root(), 1, 2);

            final ObjectChange objectChange = new ObjectChange(new Object(), List.of(rootChange));

            assertThat(objectChange.changes()).containsExactly(rootChange);
            assertThat(rootChange.fullPath()).isEmpty();
        }

        @Test
        @DisplayName("单元素结果列表是合法下界")
        void exactlyOneResult_shouldBeAccepted() {
            final ObjectChange objectChange = new ObjectChange(
                    new Object(), List.of(ChangeTreeFixtures.value(ChangeTreeFixtures.field("status"), "a", "b")));

            assertThat(objectChange.changes()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("失败路径")
    class FailurePaths {

        @Test
        @DisplayName("拒绝 null 目标对象")
        void nullTarget_shouldBeRejected() {
            assertThatThrownBy(() -> new ObjectChange(null, List.of(ChangeTreeFixtures.value(ChangeTreeFixtures.field("status"), "a", "b"))))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("拒绝 null 结果列表")
        void nullChanges_shouldBeRejected() {
            assertThatThrownBy(() -> new ObjectChange(new Object(), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("手工构造空单目标结果在构造时被拒绝")
        void emptyChanges_shouldBeRejected() {
            assertThatThrownBy(() -> new ObjectChange(new Object(), List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("顶层结果可以为变更分组，但空路径分组在分组构建入口即被拒绝，不会成为单目标结果的元素")
        void emptyPathContainer_shouldNeverReachTheTopLevel() {
            assertThatThrownBy(() -> new ContainerChange(ChangeTreeFixtures.root(),
                    List.of(ChangeTreeFixtures.value(ChangeTreeFixtures.field("status"), "a", "b"))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("只读与防御性复制")
    class ReadOnlyAndDefensiveCopy {

        @Test
        @DisplayName("结果列表不可修改")
        void changes_shouldBeReadOnly() {
            final ObjectChange objectChange = new ObjectChange(
                    new Object(), List.of(ChangeTreeFixtures.value(ChangeTreeFixtures.field("status"), "a", "b")));

            assertThatThrownBy(() -> objectChange.changes().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("构造后修改入参列表不影响结果")
        void constructor_shouldDefensivelyCopyChanges() {
            final ValueChange status = ChangeTreeFixtures.value(ChangeTreeFixtures.field("status"), "a", "b");
            final List<Change> source = new ArrayList<>(List.of(status));

            final ObjectChange objectChange = new ObjectChange(new Object(), source);
            source.clear();

            assertThat(objectChange.changes()).containsExactly(status);
        }
    }
}
