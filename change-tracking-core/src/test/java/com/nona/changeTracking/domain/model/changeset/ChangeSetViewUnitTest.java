package com.nona.changeTracking.domain.model.changeset;

import com.nona.changeTracking.domain.model.snapshot.NullNode;
import com.nona.changeTracking.domain.model.snapshot.ObjectNode;
import com.nona.changeTracking.domain.model.snapshot.PrimitiveNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ChangeSet} 两个视图的单元测试。
 * <p>
 * 覆盖 AC02-1/AC02-2/AC02-4：同一节点在完整视图与叶子视图下的完整路径、相对路径、字段名、集合归属、
 * 直接父级与载荷一致；完整视图保持前序且每个逻辑节点恰好一次；叶子视图等价于完整视图中的非分组节点；
 * 原子变化的快照载荷不参与遍历；真实空路径叶子同时出现在两种视图；不同目标的同名路径、嵌套同名集合、
 * 根集合与重复标识的定位互不合并。
 */
@DisplayName("ChangeSet 两视图单元测试")
class ChangeSetViewUnitTest {

    @Nested
    @DisplayName("完整视图前序")
    class PreOrder {

        @Test
        @DisplayName("完整视图按前序列出每个分组与原子变化各一次，且不含 ObjectChange 自身")
        void allChanges_shouldListEveryNodeOnceInPreOrder() {
            final OrderTree tree = OrderTree.canonical();

            final List<Change> all = tree.changeSet().getAllChanges();

            assertThat(all).containsExactly(
                    tree.itemsGroup(),
                    tree.item7Group(),
                    tree.quantityChange(),
                    tree.item9Added(),
                    tree.item3Removed(),
                    tree.statusChange());
        }

        @Test
        @DisplayName("单目标单结果是最小完整视图（下界）")
        void allChanges_withSingleResult_shouldHoldThatNode() {
            final ValueChange status = ChangeTreeFixtures.value(ChangeTreeFixtures.field("status"), "a", "b");
            final ChangeSet changeSet = new ChangeSet(List.of(new ObjectChange(new Object(), List.of(status))));

            assertThat(changeSet.getAllChanges()).containsExactly(status);
        }
    }

    @Nested
    @DisplayName("叶子视图等价于完整视图的非分组节点")
    class LeafSelection {

        @Test
        @DisplayName("叶子视图按相同顺序给出四类原子变化")
        void leafChanges_shouldContainAtomicChangesInFullViewOrder() {
            final OrderTree tree = OrderTree.canonical();

            final List<Change> leaves = tree.changeSet().getLeafChanges();

            assertThat(leaves).containsExactly(
                    tree.quantityChange(),
                    tree.item9Added(),
                    tree.item3Removed(),
                    tree.statusChange());
        }

        @Test
        @DisplayName("叶子视图等价于从完整视图中选择非分组节点")
        void leafChanges_shouldEqualNonGroupNodesOfTheFullView() {
            final OrderTree tree = OrderTree.canonical();

            final List<String> expected = tree.changeSet().getAllChanges().stream()
                    .filter(change -> !(change instanceof ContainerChange))
                    .map(Change::fullPath)
                    .toList();

            assertThat(tree.changeSet().getLeafChanges()).extracting(Change::fullPath)
                    .containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("原子变化的快照载荷不参与遍历：整体替换只出现一次")
        void leafChanges_shouldNotTraverseAtomicPayloads() {
            final ObjectNode oldAddressTree = new ObjectNode(Map.of("street", new PrimitiveNode("Main St")));
            final ObjectFieldChange replaced = new ObjectFieldChange(
                    ChangeTreeFixtures.field("address"), oldAddressTree, new NullNode());
            final ChangeSet changeSet = new ChangeSet(List.of(new ObjectChange(new Object(), List.of(replaced))));

            assertThat(changeSet.getAllChanges()).containsExactly(replaced);
            assertThat(changeSet.getLeafChanges()).containsExactly(replaced);
        }
    }

    @Nested
    @DisplayName("定位在两种视图下一致（四类节点）")
    class LocationConsistency {

        @Test
        @DisplayName("字段、集合字段、集合项与集合项内字段的定位事实在两种视图下逐项一致")
        void bothViews_shouldExposeIdenticalLocationFacts() {
            final OrderTree tree = OrderTree.canonical();
            final List<Change> all = tree.changeSet().getAllChanges();
            final List<Change> leaves = tree.changeSet().getLeafChanges();

            assertThat(locationOf(all, "status")).usingRecursiveComparison()
                    .isEqualTo(locationOf(leaves, "status"));
            assertThat(locationOf(all, "items[200].quantity")).usingRecursiveComparison()
                    .isEqualTo(locationOf(leaves, "items[200].quantity"));
            assertThat(locationOf(all, "items[100]")).usingRecursiveComparison()
                    .isEqualTo(locationOf(leaves, "items[100]"));
            final Change itemContainerChange = byFullPath(all, "items[200]");
            final ChangeLocation itemContainerLocation = itemContainerChange.location();
            assertThat(itemContainerLocation.fullPath()).isEqualTo("items[200]");
            assertThat(itemContainerLocation.relativePath()).isEqualTo("[200]");
            assertThat(itemContainerLocation.fieldName()).isNull();
            assertThat(itemContainerLocation.collectionFieldName()).isEqualTo("items");
            assertThat(itemContainerLocation.isParentCollection()).isTrue();

            assertThat(itemContainerChange.relativePath()).isEqualTo("[200]");
            assertThat(itemContainerChange.collectionFieldName()).isEqualTo("items");
            assertThat(itemContainerChange.isParentCollection()).isTrue();

            final Change fieldChange = byFullPath(leaves, "status");
            assertThat(fieldChange.relativePath()).isEqualTo("status");
            assertThat(fieldChange.fieldName()).isEqualTo("status");
            assertThat(fieldChange.collectionFieldName()).isNull();
            assertThat(fieldChange.isParentCollection()).isFalse();

            final Change collectionFieldChange = byFullPath(all, "items");
            assertThat(collectionFieldChange.relativePath()).isEqualTo("items");
            assertThat(collectionFieldChange.fieldName()).isEqualTo("items");
            assertThat(collectionFieldChange.collectionFieldName()).isNull();

            final Change itemChange = byFullPath(leaves, "items[100]");
            assertThat(itemChange.relativePath()).isEqualTo("[100]");
            assertThat(itemChange.fieldName()).isNull();
            assertThat(itemChange.collectionFieldName()).isEqualTo("items");
            assertThat(itemChange.isParentCollection()).isTrue();

            final Change itemFieldChange = byFullPath(leaves, "items[200].quantity");
            assertThat(itemFieldChange.relativePath()).isEqualTo("quantity");
            assertThat(itemFieldChange.fieldName()).isEqualTo("quantity");
            assertThat(itemFieldChange.collectionFieldName()).isEqualTo("items");
            assertThat(itemFieldChange.isParentCollection()).isFalse();
        }

        @Test
        @DisplayName("容器 children 中的子结果携带与完整视图相同的完整路径")
        void containerChildren_shouldCarryFullPathsInEveryEntry() {
            final OrderTree tree = OrderTree.canonical();

            assertThat(tree.itemsGroup().children()).extracting(Change::fullPath)
                    .containsExactly("items[200]", "items[100]", "items[3]");
            assertThat(tree.item7Group().children()).extracting(Change::fullPath)
                    .containsExactly("items[200].quantity");
            assertThat(tree.item7Group().children().get(0).relativePath()).isEqualTo("quantity");
        }
    }

    @Nested
    @DisplayName("空路径裁决")
    class EmptyPathRules {

        @Test
        @DisplayName("真实根值变化的空路径原子变化同时出现在完整视图与叶子视图")
        void rootValueChange_shouldAppearInBothViews() {
            final ValueChange rootChange = ChangeTreeFixtures.value(ChangeTreeFixtures.root(), 1, 2);
            final ChangeSet changeSet = new ChangeSet(List.of(new ObjectChange(new Object(), List.of(rootChange))));

            assertThat(changeSet.getAllChanges()).containsExactly(rootChange);
            assertThat(changeSet.getLeafChanges()).containsExactly(rootChange);
            assertThat(rootChange.fullPath()).isEmpty();
            assertThat(rootChange.relativePath()).isEmpty();
            assertThat(rootChange.fieldName()).isNull();
            assertThat(rootChange.collectionFieldName()).isNull();
            assertThat(rootChange.isParentCollection()).isFalse();
        }
    }

    @Nested
    @DisplayName("多目标与路径歧义")
    class MultipleTargetsAndAmbiguity {

        @Test
        @DisplayName("不同目标的同名路径分别归属各自 ObjectChange，不按路径文本合并")
        void sameFieldNameOnDifferentTargets_shouldStayWithTheirOwnTarget() {
            final Object firstTarget = new Object();
            final Object secondTarget = new Object();
            final ValueChange firstStatus = ChangeTreeFixtures.value(ChangeTreeFixtures.field("status"), "a", "b");
            final ValueChange secondStatus = ChangeTreeFixtures.value(ChangeTreeFixtures.field("status"), "c", "d");
            final ChangeSet changeSet = new ChangeSet(List.of(
                    new ObjectChange(firstTarget, List.of(firstStatus)),
                    new ObjectChange(secondTarget, List.of(secondStatus))));

            assertThat(changeSet.changes().get(0).target()).isSameAs(firstTarget);
            assertThat(changeSet.changes().get(1).target()).isSameAs(secondTarget);
            assertThat(changeSet.getAllChanges()).containsExactly(firstStatus, secondStatus);
            assertThat(changeSet.getAllChanges()).extracting(Change::fullPath)
                    .containsExactly("status", "status");
        }

        @Test
        @DisplayName("嵌套同名集合按最近集合确定归属，不按字段简称合并")
        void nestedSameNameCollections_shouldResolveTheirOwnNearestCollection() {
            final ChangeLocation outerItems = ChangeTreeFixtures.item("items", 200);
            final ChangeLocation innerItems = ChangeLocation.collectionItem(
                    ChangeTreeFixtures.field(outerItems, "items"), 101);
            final ValueChange outerName = ChangeTreeFixtures.value(ChangeTreeFixtures.field(outerItems, "name"), "a", "b");
            final ValueChange innerName = ChangeTreeFixtures.value(ChangeTreeFixtures.field(innerItems, "name"), "c", "d");
            final ChangeSet changeSet = new ChangeSet(List.of(new ObjectChange(new Object(), List.of(outerName, innerName))));

            assertThat(outerName.fullPath()).isEqualTo("items[200].name");
            assertThat(innerName.fullPath()).isEqualTo("items[200].items[101].name");
            assertThat(outerName.collectionFieldName()).isEqualTo("items");
            assertThat(innerName.collectionFieldName()).isEqualTo("items");
            assertThat(changeSet.getAllChanges()).containsExactly(outerName, innerName);
        }

        @Test
        @DisplayName("根集合的集合项不伪造集合字段名")
        void rootCollectionItem_shouldNotInventACollectionFieldName() {
            final ItemAddedChange added = ChangeTreeFixtures.added(ChangeTreeFixtures.rootItem(100), new PrimitiveNode("x"));
            final ChangeSet changeSet = new ChangeSet(List.of(new ObjectChange(new Object(), List.of(added))));

            final Change only = changeSet.getAllChanges().get(0);

            assertThat(only.fullPath()).isEqualTo("[100]");
            assertThat(only.fieldName()).isNull();
            assertThat(only.collectionFieldName()).isNull();
            assertThat(only.isParentCollection()).isTrue();
            assertThat(changeSet.getLeafChanges()).containsExactly(added);
        }

        @Test
        @DisplayName("重复标识按出现序后缀区分，不按标识文本合并")
        void duplicateIdentifiers_shouldKeepOccurrenceSuffixes() {
            final ValueChange first = ChangeTreeFixtures.value(
                    ChangeTreeFixtures.field(ChangeTreeFixtures.item("items", "A", 1), "value"), "v1", "v3");
            final ValueChange second = ChangeTreeFixtures.value(
                    ChangeTreeFixtures.field(ChangeTreeFixtures.item("items", "A", 2), "value"), "v2", "v4");
            final ChangeSet changeSet = new ChangeSet(List.of(new ObjectChange(new Object(), List.of(first, second))));

            assertThat(changeSet.getAllChanges()).extracting(Change::fullPath)
                    .containsExactly("items[A#1].value", "items[A#2].value");
            assertThat(changeSet.getLeafChanges()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("只读与重复获取")
    class ReadOnlyAndRepeatedAcquisition {

        @Test
        @DisplayName("两个视图与容器 children 都是只读列表")
        void allViews_shouldBeReadOnly() {
            final OrderTree tree = OrderTree.canonical();

            assertThatThrownBy(() -> tree.changeSet().getAllChanges().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> tree.changeSet().getLeafChanges().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> tree.itemsGroup().children().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("重复获取两个视图返回等值结果")
        void repeatedAcquisition_shouldReturnEqualViews() {
            final OrderTree tree = OrderTree.canonical();

            assertThat(tree.changeSet().getAllChanges()).isEqualTo(tree.changeSet().getAllChanges());
            assertThat(tree.changeSet().getLeafChanges()).isEqualTo(tree.changeSet().getLeafChanges());
        }

        @Test
        @DisplayName("视图访问不修改结果：目标绑定、结果列表与载荷在查询前后一致")
        void viewAccess_shouldNotModifyTheResult() {
            final OrderTree tree = OrderTree.canonical();
            final Object target = tree.objectChange().target();
            final List<Change> before = List.copyOf(tree.objectChange().changes());

            tree.changeSet().getAllChanges();
            tree.changeSet().getLeafChanges();

            assertThat(tree.objectChange().target()).isSameAs(target);
            assertThat(tree.objectChange().changes()).containsExactlyElementsOf(before);
        }
    }

    /**
     * 取完整视图或叶子视图中指定完整路径的节点。
     *
     * @param changes  视图列表
     * @param fullPath 完整路径
     * @return 该路径的节点
     */
    private static Change byFullPath(final List<Change> changes, final String fullPath) {
        return changes.stream()
                .filter(change -> change.fullPath().equals(fullPath))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No change with full path " + fullPath));
    }

    /**
     * 取指定完整路径节点的定位。
     *
     * @param changes  视图列表
     * @param fullPath 完整路径
     * @return 定位对象
     */
    private static ChangeLocation locationOf(final List<Change> changes, final String fullPath) {
        return byFullPath(changes, fullPath).location();
    }

    /**
     * 手工构造的订单结果树：保留每个节点的引用，便于逐项断言。
     */
    private static final class OrderTree {

        /**
         * 被追踪目标。
         */
        private final Object target = new Object();

        /**
         * quantity 字段变化。
         */
        private final ValueChange quantityChange = ChangeTreeFixtures.value(
                ChangeTreeFixtures.itemField("items", 200, "quantity"), 1, 2);

        /**
         * 集合项 200 的分组。
         */
        private final ContainerChange item7Group = new ContainerChange(
                ChangeTreeFixtures.item("items", 200), List.of(this.quantityChange));

        /**
         * 集合项 100 新增。
         */
        private final ItemAddedChange item9Added = ChangeTreeFixtures.added(
                ChangeTreeFixtures.item("items", 100), new PrimitiveNode("SKU-100"));

        /**
         * 集合项 3 移除。
         */
        private final ItemRemovedChange item3Removed = ChangeTreeFixtures.removed(
                ChangeTreeFixtures.item("items", 3), new PrimitiveNode("SKU-3"));

        /**
         * items 集合字段分组。
         */
        private final ContainerChange itemsGroup = new ContainerChange(
                ChangeTreeFixtures.field("items"), List.of(this.item7Group, this.item9Added, this.item3Removed));

        /**
         * status 字段变化。
         */
        private final ValueChange statusChange = ChangeTreeFixtures.value(
                ChangeTreeFixtures.field("status"), "CREATED", "PAID");

        /**
         * 单目标变更结果。
         */
        private final ObjectChange objectChange = new ObjectChange(this.target, List.of(this.itemsGroup, this.statusChange));

        /**
         * 变更集。
         */
        private final ChangeSet changeSet = new ChangeSet(List.of(this.objectChange));

        /**
         * 建立标准订单结果树。
         *
         * @return 结果树夹具
         */
        private static OrderTree canonical() {
            return new OrderTree();
        }

        /**
         * 返回单目标变更结果。
         *
         * @return 单目标变更结果
         */
        private ObjectChange objectChange() {
            return this.objectChange;
        }

        /**
         * 返回变更集。
         *
         * @return 变更集
         */
        private ChangeSet changeSet() {
            return this.changeSet;
        }

        /**
         * 返回 items 集合字段分组。
         *
         * @return items 分组
         */
        private ContainerChange itemsGroup() {
            return this.itemsGroup;
        }

        /**
         * 返回集合项 200 的分组。
         *
         * @return 集合项 200 分组
         */
        private ContainerChange item7Group() {
            return this.item7Group;
        }

        /**
         * 返回 quantity 字段变化。
         *
         * @return quantity 字段变化
         */
        private ValueChange quantityChange() {
            return this.quantityChange;
        }

        /**
         * 返回集合项 100 新增。
         *
         * @return 集合项新增
         */
        private ItemAddedChange item9Added() {
            return this.item9Added;
        }

        /**
         * 返回集合项 3 移除。
         *
         * @return 集合项移除
         */
        private ItemRemovedChange item3Removed() {
            return this.item3Removed;
        }

        /**
         * 返回 status 字段变化。
         *
         * @return status 字段变化
         */
        private ValueChange statusChange() {
            return this.statusChange;
        }
    }
}
