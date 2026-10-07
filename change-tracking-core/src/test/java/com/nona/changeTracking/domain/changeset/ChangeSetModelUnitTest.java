package com.nona.changeTracking.domain.changeset;

import com.nona.changeTracking.domain.model.changeset.Change;
import com.nona.changeTracking.domain.model.changeset.ChangeLocation;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.ContainerChange;
import com.nona.changeTracking.domain.model.changeset.ItemAddedChange;
import com.nona.changeTracking.domain.model.changeset.ObjectChange;
import com.nona.changeTracking.domain.model.changeset.ObjectFieldChange;
import com.nona.changeTracking.domain.model.changeset.ValueChange;
import com.nona.changeTracking.domain.model.snapshot.NullNode;
import com.nona.changeTracking.domain.model.snapshot.ObjectNode;
import com.nona.changeTracking.domain.model.snapshot.PrimitiveNode;
import com.nona.changeTracking.domain.model.snapshot.ValueNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结果模型安全性与只读边界单元测试（AC04-2）。
 * <p>
 * 覆盖三层结构（变更集 → 单目标结果 → 分组）的防御性复制与只读契约、原子变化载荷按引用共享且不被包装或
 * 改写，以及视图访问不改变结果内容与目标绑定。定位对象不持业务对象、完整结果节点或比较会话状态的要求
 * 由 {@code ChangeLocationUnitTest} 的字段白名单断言覆盖。
 */
@DisplayName("结果模型安全性与只读边界单元测试")
class ChangeSetModelUnitTest {

    @Nested
    @DisplayName("三层防御性复制")
    class DefensiveCopy {

        @Test
        @DisplayName("分组子结果列表在构造时复制：修改入参不影响分组")
        void containerChange_shouldCopyIncomingChildren() {
            final ChangeLocation items = ChangeLocation.field(ChangeLocation.root(), "items");
            final ValueChange quantity = new ValueChange(ChangeLocation.field(
                    ChangeLocation.collectionItem(items, 7), "quantity"), 1, 2);
            final List<Change> incoming = new ArrayList<>(List.of(quantity));

            final ContainerChange group = new ContainerChange(items, incoming);
            incoming.add(new ValueChange(ChangeLocation.field(ChangeLocation.root(), "other"), "a", "b"));
            incoming.clear();

            assertThat(group.children()).containsExactly(quantity);
        }

        @Test
        @DisplayName("单目标结果列表在构造时复制：修改入参不影响结果")
        void objectChange_shouldCopyIncomingChanges() {
            final ValueChange status = new ValueChange(ChangeLocation.field(ChangeLocation.root(), "status"), "a", "b");
            final List<Change> incoming = new ArrayList<>(List.of(status));

            final ObjectChange objectChange = new ObjectChange(new Object(), incoming);
            incoming.clear();

            assertThat(objectChange.changes()).containsExactly(status);
        }

        @Test
        @DisplayName("变更集目标列表在构造时复制：修改入参不影响变更集")
        void changeSet_shouldCopyIncomingTargets() {
            final ObjectChange objectChange = new ObjectChange(new Object(),
                    List.of(new ValueChange(ChangeLocation.field(ChangeLocation.root(), "status"), "a", "b")));
            final List<ObjectChange> incoming = new ArrayList<>(List.of(objectChange));

            final ChangeSet changeSet = new ChangeSet(incoming);
            incoming.clear();

            assertThat(changeSet.changes()).containsExactly(objectChange);
        }
    }

    @Nested
    @DisplayName("只读边界")
    class ReadOnlyBoundary {

        @Test
        @DisplayName("三层结构与两个视图列表都拒绝修改")
        void everyStructuralList_shouldBeReadOnly() {
            final ChangeLocation items = ChangeLocation.field(ChangeLocation.root(), "items");
            final ValueChange quantity = new ValueChange(ChangeLocation.field(
                    ChangeLocation.collectionItem(items, 7), "quantity"), 1, 2);
            final ContainerChange group = new ContainerChange(items, List.of(quantity));
            final ObjectChange objectChange = new ObjectChange(new Object(), List.of(group));
            final ChangeSet changeSet = new ChangeSet(List.of(objectChange));

            assertThat(throwableOf(() -> changeSet.changes().clear())).isInstanceOf(UnsupportedOperationException.class);
            assertThat(throwableOf(() -> objectChange.changes().clear())).isInstanceOf(UnsupportedOperationException.class);
            assertThat(throwableOf(() -> group.children().clear())).isInstanceOf(UnsupportedOperationException.class);
            assertThat(throwableOf(() -> changeSet.getAllChanges().clear())).isInstanceOf(UnsupportedOperationException.class);
            assertThat(throwableOf(() -> changeSet.getLeafChanges().clear())).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("载荷边界")
    class PayloadBoundary {

        @Test
        @DisplayName("原子变化载荷按引用共享快照节点，不被复制或包装")
        void itemAddedChange_shouldShareTheSnapshotPayload() {
            final ValueNode addedItem = new PrimitiveNode("SKU-9");
            final ItemAddedChange added = new ItemAddedChange(
                    ChangeLocation.collectionItem(ChangeLocation.field(ChangeLocation.root(), "items"), 9), addedItem);

            assertThat(added.addedItem()).isSameAs(addedItem);
        }

        @Test
        @DisplayName("整体替换原样携带两侧快照节点，不递归展开为载荷内部操作")
        void objectFieldChange_shouldCarryBothSnapshots() {
            final ObjectNode assigned = new ObjectNode(Map.of("street", new PrimitiveNode("Main St")));
            final NullNode cleared = new NullNode();
            final ObjectFieldChange change = new ObjectFieldChange(ChangeLocation.field(ChangeLocation.root(), "address"), assigned, cleared);

            assertThat(change.oldNode()).isSameAs(assigned);
            assertThat(change.newNode()).isSameAs(cleared);
        }

        @Test
        @DisplayName("值变化的载荷为业务值，不做快照包装")
        void valueChange_shouldCarryBusinessValues() {
            final ValueChange change = new ValueChange(ChangeLocation.field(ChangeLocation.root(), "quantity"), 1, 2);

            assertThat(change.oldValue()).isEqualTo(1);
            assertThat(change.newValue()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("查询无副作用")
    class SideEffectFreeQueries {

        @Test
        @DisplayName("视图访问不改变结果内容、目标绑定与元素顺序")
        void viewAccess_shouldNotMutateTheResult() {
            final Object target = new Object();
            final ValueChange status = new ValueChange(ChangeLocation.field(ChangeLocation.root(), "status"), "a", "b");
            final ValueChange name = new ValueChange(ChangeLocation.field(ChangeLocation.root(), "name"), "c", "d");
            final ObjectChange objectChange = new ObjectChange(target, List.of(status, name));
            final ChangeSet changeSet = new ChangeSet(List.of(objectChange));

            final List<Change> fullBefore = new ArrayList<>(changeSet.getAllChanges());
            final List<Change> leafBefore = new ArrayList<>(changeSet.getLeafChanges());

            changeSet.getAllChanges();
            changeSet.getLeafChanges();

            assertThat(objectChange.target()).isSameAs(target);
            assertThat(objectChange.changes()).containsExactly(status, name);
            assertThat(changeSet.getAllChanges()).isEqualTo(fullBefore);
            assertThat(changeSet.getLeafChanges()).isEqualTo(leafBefore);
        }

        @Test
        @DisplayName("重复获取的两个视图按值语义稳定（元素顺序与内容不变）")
        void repeatedAcquisition_shouldBeValueStable() {
            final ChangeLocation items = ChangeLocation.field(ChangeLocation.root(), "items");
            final ItemAddedChange added = new ItemAddedChange(
                    ChangeLocation.collectionItem(items, 9), new PrimitiveNode("SKU-9"));
            final ContainerChange group = new ContainerChange(items, List.of(added));
            final ChangeSet changeSet = new ChangeSet(List.of(new ObjectChange(new Object(), List.of(group))));

            assertThat(changeSet.getAllChanges()).isEqualTo(changeSet.getAllChanges());
            assertThat(changeSet.getLeafChanges()).isEqualTo(changeSet.getLeafChanges());
            assertThat(changeSet.getLeafChanges()).extracting(Change::fullPath).containsExactly("items[9]");
        }
    }

    /**
     * 执行动作并返回抛出的异常，用于只读列表断言。
     *
     * @param action 待执行动作
     * @return 抛出的异常
     */
    private static Throwable throwableOf(final Runnable action) {
        try {
            action.run();
        } catch (Throwable thrown) {
            return thrown;
        }
        throw new AssertionError("Expected the read-only list to reject the mutation");
    }
}
