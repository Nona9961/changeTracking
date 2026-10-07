package com.nona.changeTracking.domain.changeset;

import com.nona.changeTracking.domain.model.changeset.Change;
import com.nona.changeTracking.domain.model.changeset.ChangeLocation;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.ContainerChange;
import com.nona.changeTracking.domain.model.changeset.ItemAddedChange;
import com.nona.changeTracking.domain.model.changeset.ItemRemovedChange;
import com.nona.changeTracking.domain.model.changeset.ObjectChange;
import com.nona.changeTracking.domain.model.changeset.ObjectFieldChange;
import com.nona.changeTracking.domain.model.changeset.ValueChange;
import com.nona.changeTracking.domain.model.snapshot.NullNode;
import com.nona.changeTracking.domain.model.snapshot.ObjectNode;
import com.nona.changeTracking.domain.model.snapshot.PrimitiveNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 五类变更结果单元测试：统一模型只有一套变更类型，每个具体类型组合一个 {@link ChangeLocation}
 * 并承载自身载荷，原有定位访问入口（{@code path}/{@code fullPath}/{@code relativePath}/
 * {@code fieldName}/{@code collectionFieldName}/{@code isParentCollection}）是对定位的薄委托。
 * <p>
 * 覆盖四类节点的定位访问（字段、集合字段、集合项、集合项内字段）、载荷保存与密封层次的事实。
 */
@DisplayName("Change 五类结果单元测试")
class ChangeUnitTest {

    @Nested
    @DisplayName("定位访问委托")
    class LocationDelegation {

        @Test
        @DisplayName("字段位置上的字段值变化：path 与 fullPath 一致且等于定位完整路径")
        void valueChange_shouldDelegateEveryLocationAccess() {
            final ChangeLocation location = ChangeLocation.field(ChangeLocation.root(), "address");
            final ValueChange change = new ValueChange(location, "Main St", "Market St");

            assertThat(change.location()).isSameAs(location);
            assertThat(change.path()).isEqualTo(location.fullPath()).isEqualTo("address");
            assertThat(change.fullPath()).isEqualTo("address");
            assertThat(change.relativePath()).isEqualTo("address");
            assertThat(change.fieldName()).isEqualTo("address");
            assertThat(change.collectionFieldName()).isNull();
            assertThat(change.isParentCollection()).isFalse();
            assertThat(change.oldValue()).isEqualTo("Main St");
            assertThat(change.newValue()).isEqualTo("Market St");
        }

        @Test
        @DisplayName("集合项位置上的字段值变化：字段名来自集合项内字段、集合归属继承 items")
        void valueChange_insideCollectionItem_shouldInheritCollectionContext() {
            final ChangeLocation item = ChangeLocation.collectionItem(
                    ChangeLocation.field(ChangeLocation.root(), "items"), 7);
            final ValueChange change = new ValueChange(ChangeLocation.field(item, "quantity"), 1, 2);

            assertThat(change.path()).isEqualTo("items[7].quantity");
            assertThat(change.fullPath()).isEqualTo("items[7].quantity");
            assertThat(change.relativePath()).isEqualTo("quantity");
            assertThat(change.fieldName()).isEqualTo("quantity");
            assertThat(change.collectionFieldName()).isEqualTo("items");
            assertThat(change.isParentCollection()).isFalse();
        }

        @Test
        @DisplayName("集合项位置的整体替换：字段名为空、集合归属为集合字段、直接父级为集合")
        void objectFieldChange_atCollectionItem_shouldDelegateItemFacts() {
            final ChangeLocation itemLocation = ChangeLocation.collectionItem(
                    ChangeLocation.field(ChangeLocation.root(), "items"), 100);
            final ObjectFieldChange change = new ObjectFieldChange(itemLocation, new ObjectNode(Map.of("sku", new PrimitiveNode("A"))), new NullNode());

            assertThat(change.location()).isSameAs(itemLocation);
            assertThat(change.path()).isEqualTo("items[100]");
            assertThat(change.relativePath()).isEqualTo("[100]");
            assertThat(change.fieldName()).isNull();
            assertThat(change.collectionFieldName()).isEqualTo("items");
            assertThat(change.isParentCollection()).isTrue();
            assertThat(change.newNode()).isInstanceOf(NullNode.class);
        }

        @Test
        @DisplayName("集合字段位置上的分组：字段名是集合字段本身，直接父级不是集合")
        void containerChange_atCollectionField_shouldDelegateFieldFacts() {
            final ChangeLocation itemsField = ChangeLocation.field(ChangeLocation.root(), "items");
            final ItemAddedChange added = new ItemAddedChange(
                    ChangeLocation.collectionItem(itemsField, 9), new PrimitiveNode("SKU-9"));
            final ContainerChange group = new ContainerChange(itemsField, List.of(added));

            assertThat(group.location()).isSameAs(itemsField);
            assertThat(group.path()).isEqualTo("items");
            assertThat(group.relativePath()).isEqualTo("items");
            assertThat(group.fieldName()).isEqualTo("items");
            assertThat(group.collectionFieldName()).isNull();
            assertThat(group.isParentCollection()).isFalse();
            assertThat(group.children()).containsExactly(added);
        }

        @Test
        @DisplayName("根集合下的集合项新增：不伪造集合字段名，完整路径为项路径")
        void itemAddedChange_ofRootCollection_shouldNotInventFieldName() {
            final ChangeLocation rootItem = ChangeLocation.collectionItem(ChangeLocation.root(), 100);
            final ItemAddedChange added = new ItemAddedChange(rootItem, new PrimitiveNode("SKU-100"));

            assertThat(added.path()).isEqualTo("[100]");
            assertThat(added.relativePath()).isEqualTo("[100]");
            assertThat(added.fieldName()).isNull();
            assertThat(added.collectionFieldName()).isNull();
            assertThat(added.isParentCollection()).isTrue();
            assertThat(added.addedItem()).isInstanceOf(PrimitiveNode.class);
        }

        @Test
        @DisplayName("集合项移除：定位与载荷保存移除项的快照表示")
        void itemRemovedChange_shouldCarryTheRemovedSnapshot() {
            final ChangeLocation rootItem = ChangeLocation.collectionItem(ChangeLocation.root(), 3);
            final PrimitiveNode removedItem = new PrimitiveNode("SKU-3");
            final ItemRemovedChange removed = new ItemRemovedChange(rootItem, removedItem);

            assertThat(removed.location()).isSameAs(rootItem);
            assertThat(removed.removedItem()).isSameAs(removedItem);
            assertThat(removed.fullPath()).isEqualTo("[3]");
        }
    }

    @Nested
    @DisplayName("统一模型的事实")
    class UnifiedModelFacts {

        @Test
        @DisplayName("Change 密封层次只有五个具体实现，且不允许第二套分组类型")
        void changeHierarchy_shouldPermitExactlyFiveImplementations() {
            assertThat(Change.class.isSealed()).isTrue();
            assertThat(Set.of(Change.class.getPermittedSubclasses()))
                    .containsExactlyInAnyOrder(ValueChange.class, ObjectFieldChange.class, ContainerChange.class,
                            ItemAddedChange.class, ItemRemovedChange.class);
        }

        @Test
        @DisplayName("同一位置与载荷的同类结果按值相等；载荷不同则不相等")
        void results_shouldCompareByValue() {
            final ValueChange first = new ValueChange(ChangeLocation.field(ChangeLocation.root(), "status"), "a", "b");
            final ValueChange same = new ValueChange(ChangeLocation.field(ChangeLocation.root(), "status"), "a", "b");
            final ValueChange different = new ValueChange(ChangeLocation.field(ChangeLocation.root(), "status"), "a", "c");

            assertThat(first).isEqualTo(same);
            assertThat(first.hashCode()).isEqualTo(same.hashCode());
            assertThat(first).isNotEqualTo(different);
        }

        @Test
        @DisplayName("结果可以同时被完整视图与单目标结果引用，定位不因所在结构改变")
        void sameResult_shouldKeepItsLocationInBothContainers() {
            final ValueChange status = new ValueChange(ChangeLocation.field(ChangeLocation.root(), "status"), "a", "b");
            final ObjectChange objectChange = new ObjectChange(new Object(), List.of(status));
            final ChangeSet changeSet = new ChangeSet(List.of(objectChange));

            assertThat(changeSet.getLeafChanges().get(0).fullPath()).isEqualTo("status");
            assertThat(objectChange.changes().get(0).fullPath()).isEqualTo("status");
        }
    }
}
